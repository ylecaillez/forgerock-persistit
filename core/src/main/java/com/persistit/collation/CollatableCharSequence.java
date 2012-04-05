/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.persistit.collation;

/**
 * <p>
 * Convert string-valued data to and from a form that sorts correctly under a
 * binary ordering for use in B-Tree keys. This interface defines two main
 * methods, {@link #encode(byte[], int, int, byte[], int, int)} and
 * {@link #decode(byte[], int, int, byte[], int, int)}. These methods are
 * responsible for encoding and decoding strings within Persistit {@link Key}
 * instances in such a way that binary comparisons of the key bytes will result
 * in correct locale-specific ordering of the keys.
 * </p>
 * 
 * <p>
 * The <code>encode</code> method populates two byte arrays, one containing the
 * "keyBytes" and the other containing the "caseBytes". The keyBytes array is an
 * encoding of a derived sort key for the string. The caseBytes contains the
 * information needed to recover the original string when combined with the
 * keyBytes.
 * </p>
 * 
 * <p>
 * As might be expected, the <code>decode</code> method combines the keyBytes
 * and caseBytes data to recreate the original string.
 * </p>
 * 
 * <p>
 * An instance of this interface holds a small-integer collationId. Its value is
 * intended to be used as a handle by which an implementation of a specific
 * collation scheme can be looked up. CollationId value zero is reserved to
 * specify collation consistent with the default UTF-8 encoding scheme. Small
 * positive integers are used to specify collation schemes for supported
 * languages.
 * </p>
 * 
 * <p>
 * You must specify the collationId before calling the <code>encode</code>
 * method. The implementation of <code>encode</code> should encode the
 * collationId into the caseBytes array.
 * </p>
 * 
 * <p>
 * You may specify the collationId before calling the <code>decode</code>
 * method, in which case the value encoded in the caseBytes will be verified
 * against the supplied value. Alternatively you may specify a value of -1 in
 * which case the value encoded in caseBytes will be used without verification.
 * </p>
 * 
 * <p>
 * As an option specified by passing a value of zero as caseBytesMaximumLength,
 * the <code>encode</code> method may not write any case bytes. In this case,
 * the value of {@link #getCollationId()} will be used when decoding the string.
 * </p>
 * 
 * @author peter
 * 
 */
public interface CollatableCharSequence extends CharSequence, Appendable {

    /**
     * @return the current collationId
     */
    int getCollationId();

    /**
     * Change the current collationId.
     * 
     * @param collationId
     *            identifier of a collation scheme. Values -1 and 0 have special
     *            meaning described {@link #decode}.
     */
    void setCollationId(int collationId);

    /**
     * <p>
     * Encode a string value for proper collation using the supplied array
     * specifications to hold the keyBytes and caseBytes. The encoding scheme is
     * specified by the collationId value last assigned via
     * {@link #setCollationId(int)}.
     * </p>
     * <p>
     * An implementation of this method may only use non-zero bytes to encode the
     * keyBytes field. Zero bytes are reserved to delimit the ends of key segments.
     * Since this method is used in performance-critical code paths, the encode
     * produced by this method is not verified for correctness. Therefore an incorrect
     * implementation will produce anomalous results. 
     * </p>
     * <p>
     * The return value is a long that holds the length of the case bytes in its
     * upper 32 bits and the length of the keyBytes in its lower 32 bits. The
     * method writes the keyBytes and caseBytes into the supplied byte arrays
     * with specified upper bounds on sizes for each. If the length is exceeded,
     * this method throws an ArrayIndexOutOfBoundsException.
     * </p>
     * 
     * @param keyBytes
     *            Byte array into which keyBytes are written
     * @param keyBytesOffset
     *            offset of first byte of keyBytes
     * @param keyBytesMaximumLength
     *            maximum permissible length of keyBytes
     * @param caseBytes
     *            Byte array into which caseBytes are optionally written
     * @param caseBytesOffset
     *            offset of first byte of caseBytes
     * @param caseBytesMaximumLength
     *            maximum permissible length of caseBytes, or zero to avoid
     *            producing them
     * @return ((caseBytes length) << 32) + (keyBytes length)
     * @throws ArrayIndexOutOfBoundsException
     *             if either maximum array length is exceeded
     * @throws IllegalArgumentException
     *             if the implementation determines that the string it
     *             represents cannot be encoded
     */
    long encode(byte[] keyBytes, int keyBytesOffset, int keyBytesMaximumLength, byte[] caseBytes, int caseBytesOffset,
            int caseBytesMaximumLength);

    /**
     * <p>
     * Combine the supplied byte arrays previously produced by the
     * {@link #encode} method to produce a
     * string. The result of calling this method is equivalent to calling the
     * {@link #append(char)} method for each character produced by combining the
     * two byte arrays.
     * </p>
     * <p>
     * It is intended that the caseBytes should encode the collationId value
     * used when the <code>encode</code> method was called. That value (E)
     * interacts with the current value of {@link #getCollationId()} (C) in the
     * following way:
     * <ul>
     * <li>if E == C then the string is decoded using the collation scheme
     * identified by E.</li>
     * <li>if E is non-zero and C is -1 then the string is decoded using the
     * collation scheme identified by E and the current collationId of this
     * CollatableCharSequence is set to E as if setCollationId(E) were called.</li>
     * <li>if E>0, C>0 and E != C then this method throws an
     * IllegalArgumentException.
     * <li>if caseBytes is empty then the collation scheme identified by C is
     * used to decode the string, but the result is likely to be different from
     * the original string due to missing information.</li>
     * <li>if caseBytes is empty and C is -1 or 0 then the default Persistit
     * UTF-8 binary encoding is used to create a string from the keyBytes</li>
     * </ul>
     * </p>
     * 
     * @param keyBytes
     *            Byte array containing keyBytes
     * @param keyBytesOffset
     *            offset of first byte of keyBytes
     * @param keyBytesLength
     *            length of keyBytes
     * @param caseBytes
     *            Byte array containing caseBytes
     * @param caseBytesOffset
     *            offset of first byte of caseBytes
     * @param caseBytesLength
     *            length of caseBytes
     * @return length of the resulting string
     * @throws IllegalArgumentException
     *             if the collationId encoded in caseBytes does not match the
     *             current value of {@link #getCollationId()}.
     */
    int decode(byte[] keyBytes, int keyBytesOffset, int keyBytesLength, byte[] caseBytes, int caseBytesOffset,
            int caseBytesLength);

    /**
     * Decode the collationId from the supplied caseBytes.
     * 
     * @param caseBytes
     *            Byte array containing caseBytes
     * @param caseBytesOffset
     *            offset of first byte of caseBytes
     * @param caseBytesLength
     *            length of caseBytes
     * @return the collationId encoded in the supplied caseBytes, or zero if the
     *         caseBytesLength is zero.
     */
    int decodeCollationId(byte[] caseBytes, int caseBytesOffset, int caseBytesLength);
}
