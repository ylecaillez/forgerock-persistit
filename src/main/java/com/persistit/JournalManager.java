package com.persistit;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.persistit.TimestampAllocator.Checkpoint;
import com.persistit.exception.CorruptJournalException;
import com.persistit.exception.PersistitException;
import com.persistit.exception.PersistitIOException;

/**
 * Manages the disk-based I/O journal. The journal contains both committed
 * transactions and images of updated pages.
 * 
 * @author peter
 * 
 */
public class JournalManager {

    private final static long GIG = 1024 * 1024 * 1024;

    public final static long DEFAULT_JOURNAL_SIZE = GIG;

    public final static long MINIMUM_JOURNAL_SIZE = GIG / 64;

    public final static long MAXIMUM_JOURNAL_SIZE = GIG * 64;

    public final static long ROLLOVER_THRESHOLD = 1024 * 1024;

    public final static int DEFAULT_BUFFER_SIZE = 4 * 1024 * 1024;

    public final static int DEFAULT_READ_BUFFER_SIZE = 64 * 1024;

    public final static long DEFAULT_FLUSH_INTERVAL = 100;

    public final static long DEFAULT_COPIER_INTERVAL = 1000;

    public final static long DEFAULT_CLOSING_INTERVAL = 30000;

    public final static int MAXIMUM_MAPPED_HANDLES = 4096;

    private final static String PATH_FORMAT = "%s.%016d";

    private final static Pattern PATH_PATTERN = Pattern
            .compile(".+\\.(\\d{16})");

    private final static int DEFAULT_PAGE_MAP_SIZE_BASE = 1000;

    private final static int DEFAULT_MINIMUM_URGENCY = 2;

    private final static long IO_NANOSEC_PER_INTERVAL = 100000000L;

    private final static float IO_DECAY = 0.66f;

    private final static float IO_NORMALIZE = 100f / 27f;

    private final static int DEFAULT_IO_RATE_MAX = 100;

    private final static int DEFAULT_IO_RATE_MIN = 2;

    private final static float DEFAULT_IO_RATE_SLEEP_MULTIPLIER = 0.5f;

    private final Map<VolumePage, FileAddress> _pageMap = new HashMap<VolumePage, FileAddress>();

    private final Map<File, FileChannel> _readChannelMap = new HashMap<File, FileChannel>();

    private final Map<VolumeDescriptor, Integer> _volumeToHandleMap = new HashMap<VolumeDescriptor, Integer>();

    private final Map<Integer, VolumeDescriptor> _handleToVolumeMap = new HashMap<Integer, VolumeDescriptor>();

    private final Map<TreeDescriptor, Integer> _treeToHandleMap = new HashMap<TreeDescriptor, Integer>();

    private final Map<Integer, TreeDescriptor> _handleToTreeMap = new HashMap<Integer, TreeDescriptor>();

    private final Persistit _persistit;

    private File _writeChannelFile;

    private FileChannel _writeChannel;

    private long _maximumFileSize;

    private int _writeBufferSize = DEFAULT_BUFFER_SIZE;

    private MappedByteBuffer _writeBuffer;

    private long _writeBufferAddress = 0;

    private final JournalFlusher _flusherTask;

    private final JournalCopier _copierTask;

    private AtomicBoolean _closed = new AtomicBoolean();

    private AtomicBoolean _copying = new AtomicBoolean();

    private AtomicBoolean _flushing = new AtomicBoolean();

    private AtomicBoolean _suspendCopying = new AtomicBoolean();

    private File _directory;

    private final byte[] _bytes = new byte[4096];

    /**
     * Journal file generation - serves as suffix on file name
     */
    private long _currentGeneration;

    private long _firstGeneration;

    private int _handleCounter = 0;

    private boolean _recovered;

    private Checkpoint _lastValidCheckpoint = new Checkpoint(0, 0);

    private FileAddress _dirtyRecoveryFileAddress = null;

    private int _ioRate;

    private long _ioTime;

    private volatile long _journaledPageCount = 0;

    private volatile long _copyBackCount = 0;

    /**
     * Tunable parameters that determine how vigorously the copyBack thread
     * performs I/O. Hopefully we can set good defaults and not expose these as
     * knobs.
     */
    private volatile long _flushInterval = DEFAULT_FLUSH_INTERVAL;

    private volatile long _copierInterval = DEFAULT_COPIER_INTERVAL;

    private volatile boolean _copyFast = false;

    private volatile int _pageMapSizeBase = DEFAULT_PAGE_MAP_SIZE_BASE;

    private volatile int _minimumUrgency = DEFAULT_MINIMUM_URGENCY;

    private volatile long _copierTimestampLimit = Long.MAX_VALUE;

    private volatile int _ioRateMin = DEFAULT_IO_RATE_MIN;

    private volatile int _ioRateMax = DEFAULT_IO_RATE_MAX;

    private volatile float _ioRateSleepMultiplier = DEFAULT_IO_RATE_SLEEP_MULTIPLIER;

    private static class JournalNotClosedException extends Exception {

        private static final long serialVersionUID = 1L;

        final FileAddress _fileAddress;

        public JournalNotClosedException(final FileAddress fa) {
            _fileAddress = fa;
        }
    }

    public JournalManager(final Persistit persistit) {
        _persistit = persistit;
        _flusherTask = new JournalFlusher();
        _copierTask = new JournalCopier();
    }

    public void init(final String path, final long maximumSize)
            throws PersistitException {
        _directory = new File(path).getAbsoluteFile();
        _maximumFileSize = maximumSize;
        _closed.set(false);
    }

    public void startThreads() {
        _copierTask.start();
        _flusherTask.start();
    }

    public synchronized int getPageMapSize() {
        return _pageMap.size();
    }

    public synchronized long getFirstGeneration() {
        return _firstGeneration;
    }

    public synchronized long getCurrentGeneration() {
        return _currentGeneration;
    }

    public synchronized File getCurrentFile() {
        return _writeChannelFile;
    }

    public synchronized FileAddress getDirtyRecoveryFileAddress() {
        return _dirtyRecoveryFileAddress;
    }

    public long getMaximumFileSize() {
        return _maximumFileSize;
    }

    public void set_maximumFileSize(long maximumFileSize) {
        _maximumFileSize = maximumFileSize;
    }

    public boolean isCopyingSuspended() {
        return _suspendCopying.get();
    }

    public void setCopyingSuspended(boolean suspendCopying) {
        _suspendCopying.set(suspendCopying);
    }

    public long getFlushInterval() {
        return _flusherTask.getPollInterval();
    }

    public void setFlushInterval(long flushInterval) {
        _flusherTask.setPollInterval(flushInterval);
    }

    public long getCopierInterval() {
        return _copierTask.getPollInterval();
    }

    public void setCopierInterval(long copierInterval) {
        _copierTask.setPollInterval(copierInterval);
    }

    public int getMinimumUrgency() {
        return _minimumUrgency;
    }

    public void setMinimumUrgency(int minimumUrgency) {
        _minimumUrgency = minimumUrgency;
    }

    public int getRateIOMin() {
        return _ioRateMin;
    }

    public void setRateIOMin(int ioMin) {
        _ioRateMin = ioMin;
    }

    public int getRateIOMax() {
        return _ioRateMax;
    }

    public void setRateIOMax(int ioMax) {
        _ioRateMax = ioMax;
    }

    public boolean isClosed() {
        return _closed.get();
    }

    public boolean isCopying() {
        return _copying.get();
    }

    public File getDirectory() {
        return _directory;
    }

    public boolean isRecovered() {
        return _recovered;
    }

    public int getIORate() {
        return _ioRate;
    }

    public long getJournaledPageCount() {
        return _journaledPageCount;
    }

    public long getCopyBackCount() {
        return _copyBackCount;
    }

    public boolean readPageFromJournal(final Buffer buffer)
            throws PersistitIOException {
        final int bufferSize = buffer.getBufferSize();
        final long pageAddress = buffer.getPageAddress();
        final ByteBuffer bb = buffer.getByteBuffer();

        final Volume volume = buffer.getVolume();
        final VolumePage vp = new VolumePage(new VolumeDescriptor(volume),
                pageAddress, 0);
        final FileAddress fa;

        synchronized (this) {
            fa = _pageMap.get(vp);
        }

        if (fa == null) {
            return false;
        }
        long recordPageAddress = readPageBufferFromJournal(vp, fa, bb, true);

        if (pageAddress != recordPageAddress) {
            throw new CorruptJournalException("Record at " + fa
                    + " is not volume/page " + vp);
        }

        if (bb.limit() != bufferSize) {
            throw new CorruptJournalException("Record at " + fa
                    + " is wrong size: expected/actual=" + bufferSize + "/"
                    + bb.limit());
        }
        return true;
    }

    private long readPageBufferFromJournal(final VolumePage vp,
            final FileAddress fa, final ByteBuffer bb, final boolean chargeIO)
            throws PersistitIOException, CorruptJournalException {
        final FileChannel fc = getFileChannel(fa.getFile());
        try {
            final byte[] bytes = bb.array();
            bb.limit(JournalRecord.PA.OVERHEAD).position(0);
            readFully(bb, fc, fa.getAddress(), fa);
            if (bb.position() < JournalRecord.PA.OVERHEAD) {
                throw new CorruptJournalException("Record at " + fa
                        + " is incomplete");
            }
            final int type = JournalRecord.PA.getType(bytes);
            final int payloadSize = JournalRecord.PA.getLength(bytes)
                    - JournalRecord.PA.OVERHEAD;
            final int leftSize = JournalRecord.PA.getLeftSize(bytes);
            final int bufferSize = JournalRecord.PA.getBufferSize(bytes);
            final long pageAddress = JournalRecord.PA.getPageAddress(bytes);

            if (type != JournalRecord.PA.TYPE) {
                throw new CorruptJournalException("Record at " + fa
                        + " is not a PAGE record");
            }

            if (leftSize < 0 || payloadSize < leftSize
                    || payloadSize > bufferSize) {
                throw new CorruptJournalException("Record at " + fa
                        + " invalid sizes: recordSize= " + payloadSize
                        + " leftSize=" + leftSize + " bufferSize=" + bufferSize);
            }

            bb.limit(payloadSize).position(0);
            readFully(bb, fc, fa.getAddress() + JournalRecord.PA.OVERHEAD, fa);

            if (leftSize > 0) {
                final int rightSize = payloadSize - leftSize;
                System.arraycopy(bytes, leftSize, bytes,
                        bufferSize - rightSize, rightSize);
                Arrays.fill(bytes, leftSize, bufferSize - rightSize, (byte) 0);
            }
            bb.limit(bufferSize).position(0);
            if (chargeIO) {
                ioRate(1);
            }
            return pageAddress;
        } catch (IOException ioe) {
            throw new PersistitIOException(ioe);
        }
    }

    private void readFully(final ByteBuffer bb, final FileChannel fc,
            final long address, final FileAddress fa) throws IOException,
            CorruptJournalException {
        long a = address;
        while (bb.remaining() > 0) {
            int count = fc.read(bb, a);
            if (count < 0) {
                throw new CorruptJournalException("End of file at " + fa + ":"
                        + address);
            }
            a += count;
        }
    }

    public synchronized void writeCheckpointToJournal(
            final Checkpoint checkpoint) throws PersistitIOException {
        if (!_recovered) {
            return;
        }
        // Make sure all prior journal entries are committed to disk before
        // writing this record.
        force();
        writeBuffer(JournalRecord.CP.OVERHEAD);
        final long address = _writeBufferAddress + _writeBuffer.position();
        writeBuffer(JournalRecord.CP.OVERHEAD);
        JournalRecord.CP.putLength(_bytes, JournalRecord.CP.OVERHEAD);
        JournalRecord.CP.putType(_bytes, JournalRecord.CP.TYPE);
        JournalRecord.CP.putTimestamp(_bytes, checkpoint.getTimestamp());
        JournalRecord.CP.putSystemTimeMillis(_bytes, checkpoint
                .getSystemTimeMillis());
        _writeBuffer.put(_bytes, 0, JournalRecord.CP.OVERHEAD);
        force();
        _lastValidCheckpoint = checkpoint;
        ioRate(1);

        if (_persistit.getLogBase().isLoggable(LogBase.LOG_CHECKPOINT_WRITTEN)) {
            _persistit.getLogBase().log(
                    LogBase.LOG_CHECKPOINT_WRITTEN,
                    checkpoint,
                    new FileAddress(_writeChannelFile, address, checkpoint
                            .getTimestamp()));
        }
    }

    public synchronized void writePageToJournal(final Buffer buffer)
            throws PersistitIOException {

        final Volume volume = buffer.getVolume();
        final int available = buffer.getAvailableSize();
        final int recordSize = JournalRecord.PA.OVERHEAD
                + buffer.getBufferSize() - available;
        long address = -1;
        int handle = handleForVolume(volume);

        if (writeBuffer(recordSize)) {
            handle = handleForVolume(volume);
        }
        address = _writeBufferAddress + _writeBuffer.position();
        final int leftSize = available == 0 ? 0 : buffer.getAlloc() - available;
        JournalRecord.PA.putLength(_bytes, recordSize);
        JournalRecord.PA.putVolumeHandle(_bytes, handle);
        JournalRecord.PA.putType(_bytes);
        JournalRecord.PA.putTimestamp(_bytes, buffer.isTransient() ? -1
                : buffer.getTimestamp());
        JournalRecord.PA.putLeftSize(_bytes, leftSize);
        JournalRecord.PA.putBufferSize(_bytes, buffer.getBufferSize());
        JournalRecord.PA.putPageAddress(_bytes, buffer.getPageAddress());
        _writeBuffer.put(_bytes, 0, JournalRecord.PA.OVERHEAD);
        final int payloadSize = recordSize - JournalRecord.PA.OVERHEAD;

        if (leftSize > 0) {
            final int rightSize = payloadSize - leftSize;
            _writeBuffer.put(buffer.getBytes(), 0, leftSize);
            _writeBuffer.put(buffer.getBytes(), buffer.getBufferSize()
                    - rightSize, rightSize);
        } else {
            _writeBuffer.put(buffer.getBytes());
        }

        final VolumePage vp = new VolumePage(new VolumeDescriptor(volume),
                buffer.getPageAddress(), buffer.getTimestamp());
        final FileAddress fa = new FileAddress(_writeChannelFile, address,
                buffer.getTimestamp());
        _pageMap.put(vp, fa);
        _journaledPageCount++;

        ioRate(1);

        Debug.$assert(JournalRecord.getLength(_bytes) == recordSize);
        if (buffer.getPageAddress() != 0) {
            Debug.$assert(buffer.getPageAddress() == JournalRecord.PA
                    .getPageAddress(_bytes));
        }
    }

    public synchronized int handleForVolume(final Volume volume)
            throws PersistitIOException {
        final VolumeDescriptor vd = new VolumeDescriptor(volume);
        Integer handle = _volumeToHandleMap.get(vd);
        if (handle == null) {
            handle = Integer.valueOf(++_handleCounter);
            JournalRecord.IV.putType(_bytes);
            JournalRecord.IV.putHandle(_bytes, handle.intValue());
            JournalRecord.IV.putVolumeId(_bytes, volume.getId());
            JournalRecord.IV.putTimestamp(_bytes, 0); // TODO
            JournalRecord.IV.putVolumeName(_bytes, volume.getPath());
            final int recordSize = JournalRecord.IV.getLength(_bytes);
            writeBuffer(recordSize);
            _writeBuffer.put(_bytes, 0, recordSize);
            if (_volumeToHandleMap.size() >= MAXIMUM_MAPPED_HANDLES) {
                _volumeToHandleMap.clear();
                _handleToVolumeMap.clear();
            }
            _volumeToHandleMap.put(vd, handle);
            _handleToVolumeMap.put(handle, vd);
        }
        return handle.intValue();
    }

    public synchronized int handleForTree(final TreeDescriptor tree)
            throws PersistitIOException {
        Integer handle = _treeToHandleMap.get(tree);
        if (handle == null) {
            handle = Integer.valueOf(++_handleCounter);
            JournalRecord.IT.putType(_bytes);
            JournalRecord.IT.putHandle(_bytes, handle.intValue());
            JournalRecord.IT.putTimestamp(_bytes, 0);
            JournalRecord.IV.putTimestamp(_bytes, 0); // TODO
            JournalRecord.IV.putVolumeName(_bytes, tree.getTreeName());
            final int recordSize = JournalRecord.IV.getLength(_bytes);
            writeBuffer(recordSize);
            _writeBuffer.put(_bytes, 0, recordSize);
            if (_treeToHandleMap.size() >= MAXIMUM_MAPPED_HANDLES) {
                _treeToHandleMap.clear();
                _handleToTreeMap.clear();
            }
            _treeToHandleMap.put(tree, handle);
            _handleToTreeMap.put(handle, tree);
        }
        return handle.intValue();
    }

    public synchronized void rollover() throws PersistitIOException {
        _currentGeneration++;
        try {
            closeWriteChannel();
            final File file = new File(String.format(PATH_FORMAT, _directory,
                    _currentGeneration));
            final RandomAccessFile raf = new RandomAccessFile(file, "rw");
            _writeChannel = raf.getChannel();
            _writeChannelFile = file;
        } catch (IOException e) {
            throw new PersistitIOException(e);
        }

        _writeBufferAddress = 0;
        // Ensure new handle-map copies in every journal file
        _handleToTreeMap.clear();
        _handleToVolumeMap.clear();
        _volumeToHandleMap.clear();
        _treeToHandleMap.clear();
    }

    public synchronized void recover() throws PersistitException {
        if (_recovered) {
            throw new IllegalStateException("Recovery already completed");
        }
        _pageMap.clear();

        final Map<VolumePage, List<FileAddress>> pageMap = new HashMap<VolumePage, List<FileAddress>>();

        _firstGeneration = Long.MAX_VALUE;
        _currentGeneration = -1;

        final File[] files = files();
        _dirtyRecoveryFileAddress = null;
        for (final File file : files) {
            try {
                if (_dirtyRecoveryFileAddress != null) {
                    _persistit.getLogBase().log(
                            LogBase.LOG_INIT_RECOVER_TERMINATE, file);
                    // Don't process any files after a dirty record
                    continue;
                }
                // Each journal file must rewrite all volume and tree handles
                _handleToVolumeMap.clear();
                _volumeToHandleMap.clear();
                _handleToTreeMap.clear();
                _treeToHandleMap.clear();

                final FileChannel channel = new FileInputStream(file)
                        .getChannel();
                long bufferAddress = 0;
                while (bufferAddress < channel.size()) {
                    final long size = Math.min(channel.size() - bufferAddress,
                            _writeBufferSize);
                    final MappedByteBuffer readBuffer = channel.map(
                            MapMode.READ_ONLY, bufferAddress, size);
                    try {
                        while (recoverOneRecord(file, bufferAddress,
                                readBuffer, pageMap)) {
                            // nothing to do - work is performed in
                            // recoverOneRecord.
                        }
                    } catch (JournalNotClosedException e) {
                        _dirtyRecoveryFileAddress = e._fileAddress;
                        break;
                    }
                    bufferAddress += readBuffer.position();
                }
                final long generation = fileGeneration(file);
                if (generation >= 0) {
                    _currentGeneration = Math.max(generation,
                            _currentGeneration);
                    _firstGeneration = Math.min(generation, _firstGeneration);
                }
            } catch (IOException ioe) {
                _dirtyRecoveryFileAddress = new FileAddress(file, 0, 0);
                _persistit.getLogBase().log(LogBase.LOG_RECOVERY_FAILURE, ioe,
                        _dirtyRecoveryFileAddress);
            }
        }
        if (_firstGeneration == Long.MAX_VALUE) {
            _firstGeneration = 0;
        }
        if (_currentGeneration == -1) {
            _currentGeneration = 0;
        }
        _recovered = true;
    }

    private boolean recoverOneRecord(final File file, final long bufferAddress,
            final MappedByteBuffer readBuffer,
            final Map<VolumePage, List<FileAddress>> pageMap)
            throws PersistitException, JournalNotClosedException {

        final int from = readBuffer.position();
        if (readBuffer.remaining() < JournalRecord.OVERHEAD) {
            return false;
        }
        readBuffer.mark();
        readBuffer.get(_bytes, 0, JournalRecord.OVERHEAD);
        final int type = JournalRecord.getType(_bytes);
        final long timestamp = JournalRecord.getTimestamp(_bytes);
        switch (type) {

        case JournalRecord.TYPE_IV: {
            final int recordSize = JournalRecord.getLength(_bytes);
            if (recordSize > JournalRecord.IV.MAX_LENGTH) {
                throw new CorruptJournalException(
                        "IV JournalRecord too long: "
                                + recordSize
                                + " bytes at position "
                                + new FileAddress(file, bufferAddress + from,
                                        timestamp));
            }
            if (recordSize + from > readBuffer.limit()) {
                readBuffer.reset();
                return false;
            }
            readBuffer.get(_bytes, JournalRecord.OVERHEAD, recordSize
                    - JournalRecord.OVERHEAD);
            final Integer handle = Integer.valueOf(JournalRecord.IV
                    .getHandle(_bytes));
            final String path = JournalRecord.IV.getVolumeName(_bytes);
            final long volumeId = JournalRecord.IV.getVolumeId(_bytes);
            VolumeDescriptor vd = new VolumeDescriptor(path, volumeId);
            _handleToVolumeMap.put(handle, vd);
            _volumeToHandleMap.put(vd, handle);

            if (_persistit.getLogBase().isLoggable(LogBase.LOG_RECOVERY_RECORD)) {
                _persistit.getLogBase().log(LogBase.LOG_RECOVERY_RECORD, "IV",
                        new FileAddress(file, bufferAddress + from, timestamp),
                        path, timestamp, null, null, null, null, null, null);
            }
            break;
        }

        case JournalRecord.TYPE_IT: {
            final int recordSize = JournalRecord.getLength(_bytes);
            if (recordSize > JournalRecord.IT.MAX_LENGTH) {
                throw new CorruptJournalException(
                        "IT JournalRecord too long: "
                                + recordSize
                                + " bytes at position "
                                + new FileAddress(file, bufferAddress + from,
                                        timestamp));
            }
            if (recordSize + from > readBuffer.limit()) {
                readBuffer.reset();
                return false;
            }
            readBuffer.get(_bytes, JournalRecord.OVERHEAD, recordSize
                    - JournalRecord.OVERHEAD);
            final Integer handle = Integer.valueOf(JournalRecord.IT
                    .getHandle(_bytes));
            final String treeName = JournalRecord.IT.getTreeName(_bytes);
            final Integer volumeHandle = Integer.valueOf(JournalRecord.IT
                    .getVolumeHandle(_bytes));
            final TreeDescriptor td = new TreeDescriptor(volumeHandle, treeName);
            _handleToTreeMap.put(handle, td);
            _treeToHandleMap.put(td, handle);
            if (_persistit.getLogBase().isLoggable(LogBase.LOG_RECOVERY_RECORD)) {
                _persistit.getLogBase()
                        .log(
                                LogBase.LOG_RECOVERY_RECORD,
                                "IT",
                                new FileAddress(file, bufferAddress + from,
                                        timestamp), treeName, timestamp, null,
                                null, null, null, null, null);
            }
            break;
        }

        case JournalRecord.TYPE_PA: {
            final int recordSize = JournalRecord.getLength(_bytes);
            if (recordSize > Buffer.MAX_BUFFER_SIZE + JournalRecord.PA.OVERHEAD) {
                throw new CorruptJournalException(
                        "PA JournalRecord too long: "
                                + recordSize
                                + " bytes at position "
                                + new FileAddress(file, bufferAddress + from,
                                        timestamp));
            }
            if (recordSize + from > readBuffer.limit()) {
                readBuffer.reset();
                return false;
            }
            readBuffer.get(_bytes, JournalRecord.OVERHEAD,
                    JournalRecord.PA.OVERHEAD - JournalRecord.OVERHEAD);
            readBuffer.position(from + recordSize);
            final long address = bufferAddress + from;
            final long pageAddress = JournalRecord.PA.getPageAddress(_bytes);
            final Integer volumeHandle = Integer.valueOf(JournalRecord.PA
                    .getVolumeHandle(_bytes));
            VolumeDescriptor vd = _handleToVolumeMap.get(volumeHandle);
            if (vd == null) {
                throw new CorruptJournalException(
                        "PA reference to volume "
                                + volumeHandle
                                + " is not preceded by an IV record for that handle at "
                                + new FileAddress(file, address, timestamp));
            }
            if (timestamp >= 0) {
                final VolumePage vp = new VolumePage(vd, pageAddress, timestamp);
                final FileAddress fa = new FileAddress(file, address, timestamp);
                List<FileAddress> addresses = pageMap.get(vp);
                if (addresses == null) {
                    addresses = new ArrayList<FileAddress>(2);
                    pageMap.put(vp, addresses);
                }
                addresses.add(fa);
                if (_persistit.getLogBase().isLoggable(
                        LogBase.LOG_RECOVERY_RECORD)) {
                    _persistit.getLogBase().log(
                            LogBase.LOG_RECOVERY_RECORD,
                            "PA",
                            new FileAddress(file, bufferAddress + from,
                                    timestamp), vp, timestamp, null, null,
                            null, null, null, null);
                }
            }
            break;
        }

        case JournalRecord.TYPE_RR:
        case JournalRecord.TYPE_WR:
        case JournalRecord.TYPE_TS:
        case JournalRecord.TYPE_TC:
        case JournalRecord.TYPE_TJ:
            throw new UnsupportedOperationException(
                    "Can't handle record of type " + (int) type);

        case JournalRecord.TYPE_CP:
            final int recordSize = JournalRecord.getLength(_bytes);
            if (recordSize != JournalRecord.CP.OVERHEAD) {
                throw new CorruptJournalException(
                        "CP JournalRecord has incorrect length: "
                                + recordSize
                                + " bytes at position "
                                + new FileAddress(file, bufferAddress
                                        + readBuffer.position()
                                        - JournalRecord.OVERHEAD, timestamp));
            }
            if (recordSize + from > readBuffer.limit()) {
                readBuffer.reset();
                return false;
            }
            readBuffer.get(_bytes, JournalRecord.OVERHEAD, recordSize
                    - JournalRecord.OVERHEAD);
            final long systemTimeMillis = JournalRecord.CP
                    .getSystemTimeMillis(_bytes);
            final Checkpoint checkpoint = new Checkpoint(timestamp,
                    systemTimeMillis);
            _persistit.getTimestampAllocator().updateTimestamp(timestamp);
            _lastValidCheckpoint = checkpoint;

            mergeCheckpoint(checkpoint, pageMap);

            if (_persistit.getLogBase().isLoggable(
                    LogBase.LOG_CHECKPOINT_RECOVERED)) {
                _persistit.getLogBase().log(
                        LogBase.LOG_CHECKPOINT_RECOVERED,
                        checkpoint,
                        new FileAddress(file, bufferAddress + from, checkpoint
                                .getTimestamp()));
            }
            if (_persistit.getLogBase().isLoggable(LogBase.LOG_RECOVERY_RECORD)) {
                _persistit.getLogBase().log(LogBase.LOG_RECOVERY_RECORD, "CP",
                        new FileAddress(file, bufferAddress + from, timestamp),
                        checkpoint + " _pageMap.size()=" + _pageMap.size(),
                        timestamp, null, null, null, null, null, null);
            }
            break;

        default:
            _persistit.getLogBase().log(LogBase.LOG_INIT_RECOVER_TERMINATE,
                    new FileAddress(file, bufferAddress + from, timestamp));
            throw new JournalNotClosedException(new FileAddress(file,
                    bufferAddress, -1));
        }
        return true;
    }

    private void mergeCheckpoint(final Checkpoint checkpoint,
            final Map<VolumePage, List<FileAddress>> pageMap) {
        final long timestamp = checkpoint.getTimestamp();
        for (final Iterator<Map.Entry<VolumePage, List<FileAddress>>> iterator = pageMap
                .entrySet().iterator(); iterator.hasNext();) {
            final Map.Entry<VolumePage, List<FileAddress>> entry = iterator
                    .next();
            final List<FileAddress> addresses = entry.getValue();

            long latest = Long.MIN_VALUE;
            for (int index = 0; index < addresses.size(); index++) {
                long t = addresses.get(index).getTimestamp();
                if (t <= timestamp) {
                    latest = Math.max(latest, t);
                }
            }

            boolean found = false;
            for (int index = addresses.size(); --index >= 0;) {
                FileAddress fa = addresses.get(index);
                if (fa.getTimestamp() == latest) {
                    // Debug.debug1(found);
                    found = true;
                    _pageMap.put(entry.getKey(), fa);
                }
            }
            // TODO - fold this into previous loop after debugging
            for (int index = addresses.size(); --index >= 0;) {
                FileAddress fa = addresses.get(index);
                if (fa.getTimestamp() <= timestamp) {
                    addresses.remove(index);
                }
            }

            Debug.$assert(found || latest == Long.MIN_VALUE);
            if (addresses.isEmpty()) {
                iterator.remove();
            } else {
                ((ArrayList<FileAddress>) addresses).trimToSize();
            }
        }
    }

    private long fileGeneration(final File file) {
        final Matcher matcher = PATH_PATTERN.matcher(file.getName());
        if (matcher.matches()) {
            return Long.parseLong(matcher.group(1));
        } else {
            return -1;
        }
    }

    public void close() throws PersistitIOException {

        synchronized (this) {
            _closed.set(true);
        }

        _persistit.waitForIOTaskStop(_copierTask);
        _persistit.waitForIOTaskStop(_flusherTask);

        final boolean clear;
        synchronized (this) {
            try {
                closeWriteChannel();
                for (final FileChannel channel : _readChannelMap.values()) {
                    channel.close();
                }
            } catch (IOException ioe) {
                throw new PersistitIOException(ioe);
            }
            clear = _pageMap.isEmpty();
            _readChannelMap.clear();
            _handleToTreeMap.clear();
            _handleToVolumeMap.clear();
            _volumeToHandleMap.clear();
            _treeToHandleMap.clear();
            _pageMap.clear();
            _writeChannelFile = null;
            _writeChannel = null;
            _writeBuffer = null;
            _recovered = false;
            Arrays.fill(_bytes, (byte) 0);

            if (clear) {
                final File[] files = files();
                for (final File file : files) {
                    file.delete();
                }
            }
        }
    }

    /**
     * Force all data written to the journal file to disk.
     */
    public void force() {
        final MappedByteBuffer mbb;
        synchronized (this) {
            mbb = _writeBuffer;
        }

        if (mbb != null) {
            mbb.force();
        }
    }

    private boolean writeBuffer(final int size) throws PersistitIOException {
        boolean rolled = false;
        if (_writeBuffer != null) {
            if (_writeBuffer.remaining() >= size) {
                return false;
            } else {
                _writeBufferAddress += _writeBuffer.position();
                _writeBuffer.force();
                _writeBuffer = null;
            }
        }

        if (_writeChannel == null
                || _writeBufferAddress + _writeBufferSize > _maximumFileSize) {
            rollover();
            rolled = true;
        }

        try {
            _writeBuffer = _writeChannel.map(MapMode.READ_WRITE,
                    _writeBufferAddress, _writeBufferSize);
        } catch (IOException ioe) {
            throw new PersistitIOException(ioe);
        }
        return rolled;
    }

    private void closeWriteChannel() throws IOException {
        if (_writeBuffer != null) {
            _writeBuffer.force();
            _writeBufferAddress += _writeBuffer.position();
            _writeBuffer = null;
        }
        if (_writeChannel != null) {
            _writeChannel.truncate(_writeBufferAddress);
            _writeChannel.force(true);
            _writeChannel.close();
            if (_writeBufferAddress == 0) {
                _writeChannelFile.delete();
            }
        }
    }

    private File[] files() {
        File file = _directory;
        if (!file.isDirectory()) {
            file = file.getParentFile();
            if (file == null) {
                file = new File(".");
            }
        }
        final File dir = file;

        final File[] files = dir.listFiles(new FileFilter() {

            @Override
            public boolean accept(File pathname) {
                return pathname.getParent().startsWith(dir.getPath())
                        && PATH_PATTERN.matcher(pathname.getPath()).matches();
            }

        });
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files);
        return files;
    }

    private synchronized FileChannel getFileChannel(final File file)
            throws PersistitIOException {
        try {
            FileChannel fc = _readChannelMap.get(file);
            if (fc == null) {
                final RandomAccessFile raf = new RandomAccessFile(file, "r");
                fc = raf.getChannel();
                _readChannelMap.put(file, fc);
            }
            return fc;
        } catch (IOException ioe) {
            throw new PersistitIOException(ioe);
        }
    }

    public void copyBack(final long toTimestamp) throws PersistitException {
        synchronized (this) {
            _copyFast = true;
            notifyAll();
            while (_copyFast) {
                try {
                    wait(100);
                } catch (InterruptedException ie) {
                    // ignore;
                }
            }
        }
    }

    private static class VolumeDescriptor {

        private final long _id;

        private final String _path;

        VolumeDescriptor(final String path, final long id) {
            this._path = path;
            this._id = id;
        }

        VolumeDescriptor(final Volume volume) {
            _path = volume.getPath();
            _id = volume.getId();
        }

        String getPath() {
            return _path;
        }

        long getId() {
            return _id;
        }

        @Override
        public boolean equals(final Object object) {
            final VolumeDescriptor vd = (VolumeDescriptor) object;
            return vd._path.equals(_path) && vd._id == _id;
        }

        @Override
        public int hashCode() {
            return _path.hashCode() ^ (int) _id;
        }

        @Override
        public String toString() {
            return _path;
        }

    }

    private static class TreeDescriptor {

        final int _volumeHandle;

        final String _treeName;

        TreeDescriptor(final int volumeHandle, final String treeName) {
            _volumeHandle = volumeHandle;
            _treeName = treeName;
        }

        int getVolumeHandle() {
            return _volumeHandle;
        }

        String getTreeName() {
            return _treeName;
        }

    }

    private static class VolumePage implements Comparable<VolumePage> {

        private final VolumeDescriptor _volumeDescriptor;

        private final long _page;

        VolumePage(final VolumeDescriptor vd, final long page,
                final long timestamp) {
            _volumeDescriptor = vd;
            _page = page;
        }

        VolumeDescriptor getVolumeDescriptor() {
            return _volumeDescriptor;
        }

        long getPage() {
            return _page;
        }

        @Override
        public int hashCode() {
            return _volumeDescriptor.hashCode() ^ (int) _page
                    ^ (int) (_page >>> 32);
        }

        @Override
        public boolean equals(Object obj) {
            final VolumePage vp = (VolumePage) obj;
            return _page == vp._page
                    && _volumeDescriptor.equals(_volumeDescriptor);
        }

        @Override
        public int compareTo(VolumePage vp) {
            int result = _volumeDescriptor.getPath().compareTo(
                    vp.getVolumeDescriptor().getPath());
            if (result != 0) {
                return result;
            }
            return _page > vp.getPage() ? 1 : _page < vp.getPage() ? -1 : 0;
        }

        @Override
        public String toString() {
            return _volumeDescriptor + ":" + _page;
        }
    }

    public static class FileAddress implements Comparable<FileAddress> {

        private final File _file;

        private final long _address;

        private final long _timestamp;

        FileAddress(final File file, final long address, final long timestamp) {
            _file = file;
            _address = address;
            _timestamp = timestamp;
        }

        File getFile() {
            return _file;
        }

        long getAddress() {
            return _address;
        }

        long getTimestamp() {
            return _timestamp;
        }

        @Override
        public int hashCode() {
            return _file.hashCode() ^ (int) _address ^ (int) (_address >>> 32);
        }

        @Override
        public boolean equals(final Object object) {
            final FileAddress fa = (FileAddress) object;
            return _file.equals(fa._file) && _address == fa._address;
        }

        @Override
        public int compareTo(final FileAddress fa) {
            int result = _file.compareTo(fa.getFile());
            if (result != 0) {
                return result;
            }
            if (_address != fa.getAddress()) {
                return _address > fa.getAddress() ? 1 : -1;
            }
            if (_timestamp != fa.getTimestamp()) {
                return _timestamp > fa.getTimestamp() ? 1 : -1;
            }
            return 0;
        }

        @Override
        public String toString() {
            return _file + ":" + _address + "{" + _timestamp + "}";
        }
    }

    private class JournalCopier extends IOTaskRunnable {

        JournalCopier() {
            super(_persistit);
        }

        void start() {
            start("JOURNAL_COPIER", _copierInterval);
        }

        @Override
        public void runTask() throws Exception {

            if (urgency() > _minimumUrgency && !_suspendCopying.get()) {
                _copying.set(true);
                try {
                    copierCycle();
                } finally {
                    _copying.set(false);
                }
            }
        }

        @Override
        protected boolean shouldStop() {
            return _closed.get();
        }
    }

    private class JournalFlusher extends IOTaskRunnable {

        JournalFlusher() {
            super(_persistit);
        }

        void start() {
            start("JOURNAL_FLUSHER", _flushInterval);
        }

        @Override
        protected void runTask() {
            _flushing.set(true);
            try {
                force();
            } finally {
                _flushing.set(false);
            }
        }

        @Override
        protected boolean shouldStop() {
            return _closed.get();
        }
    }

    public boolean isUrgentDemand() {
        return _copyFast;
    }

    public void setUrgentDemand(boolean urgentDemand) {
        _copyFast = urgentDemand;
    }

    /**
     * Computes an "urgency" factor that determines how vigorously the copyBack
     * thread should perform I/O. This number is computed on a scale of 0 to 10;
     * larger values are intended make the thread work harder. A value of 10
     * suggests the copier should run flat-out.
     * 
     * @return
     */
    public synchronized int urgency() {
        if (_copyFast) {
            return 10;
        }
        int urgency = _pageMap.size() / _pageMapSizeBase;
        int journalFileCount = (int) (_currentGeneration - _firstGeneration);
        if (journalFileCount > 1) {
            urgency += journalFileCount - 1;
        }
        return Math.max(urgency, 10);
    }

    private void copierCycle() throws PersistitException {
        FileAddress firstMissed = null;
        final SortedMap<VolumePage, FileAddress> sortedMap = new TreeMap<VolumePage, FileAddress>();
        final boolean wasUrgent;
        final long currentGeneration;

        synchronized (this) {
            final long timeStampUpperBound = Math.min(_lastValidCheckpoint
                    .getTimestamp(), _copierTimestampLimit);
            if (!_recovered) {
                return;
            }
            wasUrgent = _copyFast;
            currentGeneration = _currentGeneration;
            final File copyJournalFileLimit = new File(String.format(
                    PATH_FORMAT, _directory, _firstGeneration + 1));
            for (final Map.Entry<VolumePage, FileAddress> entry : _pageMap
                    .entrySet()) {
                FileAddress fa = entry.getValue();
                if (fa.getTimestamp() < timeStampUpperBound
                        && (fa.getFile().compareTo(copyJournalFileLimit) < 0 || _copyFast)) {
                    sortedMap.put(entry.getKey(), entry.getValue());
                } else {
                    if (firstMissed == null || fa.compareTo(firstMissed) < 0) {
                        firstMissed = fa;
                    }
                }
            }
        }

        Volume volume = null;
        VolumeDescriptor descriptor = null;

        final HashSet<Volume> volumes = new HashSet<Volume>();

        final ByteBuffer bb = ByteBuffer.allocate(DEFAULT_READ_BUFFER_SIZE);
        for (final Iterator<Map.Entry<VolumePage, FileAddress>> iterator = sortedMap
                .entrySet().iterator(); iterator.hasNext();) {
            final Map.Entry<VolumePage, FileAddress> entry = iterator.next();
            if (_closed.get() && !wasUrgent || _suspendCopying.get()) {
                return;
            }
            final VolumePage vp = entry.getKey();
            final FileAddress fa = entry.getValue();
            if (descriptor != vp.getVolumeDescriptor()) {
                volume = _persistit.getVolume(vp.getVolumeDescriptor()
                        .getPath());
            }
            if (volume == null || volume.isClosed()) {
                // remove from the sortedMap so that below we won't remove from
                // the pageMap.
                iterator.remove();
                // Also, don't delete the journal file yet because we may reopen
                // the
                // Volume and attempt to continue.
                if (firstMissed == null || fa.compareTo(firstMissed) < 0) {
                    firstMissed = fa;
                }
                continue;
            }
            if (volume.getId() != vp.getVolumeDescriptor().getId()) {
                throw new CorruptJournalException(vp.getVolumeDescriptor()
                        + " does not identify a valid Volume at " + fa);
            }

            final long pageAddress = readPageBufferFromJournal(vp, fa, bb,
                    false);

            if (bb.limit() != volume.getBufferSize()) {
                throw new CorruptJournalException(vp + " bufferSize "
                        + bb.limit() + " does not match " + volume
                        + " bufferSize " + volume.getBufferSize() + " at " + fa);
            }
            if (pageAddress != vp.getPage()) {
                throw new CorruptJournalException(vp
                        + " does not match page address " + pageAddress
                        + " found at " + fa);
            }
            try {
                volume.writePage(bb, pageAddress);
                volumes.add(volume);
                _copyBackCount++;
                final int ioRate = Math.min(Math.max(ioRate(0), _ioRateMin),
                        _ioRateMax);
                final long delay = (long) (_ioRateSleepMultiplier * (_copyFast ? _ioRateMin
                        : ioRate));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    // ignore
                }
            } catch (IOException ioe) {
                throw new PersistitIOException(ioe);
            }
        }

        for (final Volume vol : volumes) {
            vol.sync();
        }

        synchronized (this) {
            for (final Map.Entry<VolumePage, FileAddress> entry : sortedMap
                    .entrySet()) {
                final VolumePage vp = entry.getKey();
                final FileAddress fa = entry.getValue();
                final FileAddress fa2 = _pageMap.get(vp);
                if (fa.equals(fa2)) {
                    _pageMap.remove(vp);
                } else if (firstMissed == null || fa.compareTo(firstMissed) < 0) {
                    firstMissed = fa;
                }
            }
        }

        final File[] files = files();
        for (final File file : files) {
            if (firstMissed == null
                    || file.compareTo(firstMissed.getFile()) < 0) {
                if (!file.equals(_writeChannelFile)) {
                    file.delete();
                }
            }
        }
        File currentFile = null;
        synchronized (this) {
            if (firstMissed == null
                    && _pageMap.size() == 0
                    && _writeBuffer != null
                    && _writeBufferAddress + _writeBuffer.position() > rolloverThreshold()) {
                currentFile = _writeChannelFile;
                rollover();
            }
            if (firstMissed == null) {
                _firstGeneration = currentGeneration;
            } else {
                _firstGeneration = fileGeneration(firstMissed.getFile());
            }
        }
        if (currentFile != null) {
            currentFile.delete();
        }
        if (wasUrgent) {
            _copyFast = false;
        }
    }

    private long rolloverThreshold() {
        return _closed.get() ? 0 : ROLLOVER_THRESHOLD;
    }

    /**
     * Called at a steady rate of N operations per sec, the ioRate converges to
     * approximately N.
     */
    private synchronized int ioRate(final int delta) {
        final long now = System.nanoTime();
        final long elapsed = (now - _ioTime) / IO_NANOSEC_PER_INTERVAL;
        if (elapsed > 24) {
            _ioRate = 0;
        } else
            for (int i = (int) elapsed; --i >= 0;) {
                _ioRate *= IO_DECAY;
            }
        _ioRate += delta;
        _ioTime = now;
        return (int) (_ioRate * IO_NORMALIZE);
    }

    public static void main(final String[] args) throws Exception {
        final Persistit persistit = new Persistit();
        final JournalManager journalManager = persistit.getJournalManager();
        final String propertiesFileName = args.length > 0 ? args[0]
                : Persistit.DEFAULT_CONFIG_FILE;
        persistit.initializeProperties(persistit
                .parseProperties(propertiesFileName));
        persistit.initializeLogging();
        persistit.initializeJournal();
        journalManager.recover();

        System.out.println();
        System.out.println("Last valid checkpoint="
                + journalManager._lastValidCheckpoint);
        System.out.println();
        System.out.println("Page Map:");
        System.out.println();

        final SortedMap<VolumePage, FileAddress> sorted = new TreeMap<VolumePage, FileAddress>(
                journalManager._pageMap);
        long previous = -1;
        for (final Map.Entry<VolumePage, FileAddress> entry : sorted.entrySet()) {
            long delta = entry.getKey().getPage() - previous;
            if (delta > 1) {
                System.out.println("---" + delta + "---");
            }
            System.out.println(entry.getKey() + "  " + entry.getValue());
        }
    }
}