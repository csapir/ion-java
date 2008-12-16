/*
 * Copyright (c) 2008 Amazon.com, Inc.  All rights reserved.
 */

package com.amazon.ion;

import com.amazon.ion.impl.IonBinaryReader;


/**
 *
 */
public abstract class ReaderSystemProcessingTestCase
    extends SystemProcessingTestCase
{
    private IonReader myReader;


    protected abstract IonReader read(String text)
        throws Exception;

    protected abstract IonReader systemRead(String text)
        throws Exception;


    @Override
    protected void startIteration(String text) throws Exception
    {
        myReader = read(text);
    }

    @Override
    protected void startSystemIteration(String text) throws Exception
    {
        myReader = systemRead(text);
    }

    @Override
    protected void nextValue() throws Exception
    {
        myReader.next();
    }

    @Override
    protected SymbolTable currentSymtab() throws Exception
    {
        return myReader.getSymbolTable();
    }

    @Override
    protected void checkAnnotation(String expected)
    {
        String[] typeAnnotations = myReader.getTypeAnnotations();
        for (int i = 0; i < typeAnnotations.length; i++)
        {
            if (typeAnnotations[i].equals(expected)) return;
        }
        fail("Didn't find expected annotation: " + expected);
    }

    @Override
    protected void checkType(IonType expected)
    {
        assertSame(expected, myReader.getType());
    }

    @Override
    protected void checkInt(long expected) throws Exception
    {
        assertSame(IonType.INT, myReader.getType());
        assertEquals(expected, myReader.longValue());
    }

    @Override
    protected void checkSymbol(String expected) throws Exception
    {
        assertSame(IonType.SYMBOL, myReader.getType());
        assertEquals(expected, myReader.stringValue());
    }

    @Override
    protected void checkSymbol(String expected, int expectedSid)
        throws Exception
    {
        assertSame(IonType.SYMBOL, myReader.getType());
        assertEquals(expected, myReader.stringValue());

        // FIXME this is a bug in binary reader
        if (!(myReader instanceof IonBinaryReader)) {
            assertEquals(expectedSid, myReader.getSymbolId());
        }
    }

    @Override
    protected void checkEof()
    {
        assertFalse("not at eof", myReader.hasNext());
    }
}
