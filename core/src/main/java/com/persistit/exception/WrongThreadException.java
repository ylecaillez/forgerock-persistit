/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.persistit.exception;

/**
 * Thrown by {@link com.persistit.Exchange} when a <code>Thread</code> other
 * than the thread that created the Exchange attempts to perform an operation.
 * 
 * @version 1.0
 */
public class WrongThreadException extends RuntimeException {
    private static final long serialVersionUID = 7960498084495697551L;

    public WrongThreadException() {
        super();
    }

    public WrongThreadException(String msg) {
        super(msg);
    }

}