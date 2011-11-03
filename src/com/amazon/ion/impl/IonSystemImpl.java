// Copyright (c) 2007-2011 Amazon.com, Inc.  All rights reserved.

package com.amazon.ion.impl;

import static com.amazon.ion.SystemSymbols.ION;
import static com.amazon.ion.SystemSymbols.ION_1_0;
import static com.amazon.ion.SystemSymbols.ION_1_0_SID;
import static com.amazon.ion.SystemSymbols.ION_SHARED_SYMBOL_TABLE;
import static com.amazon.ion.SystemSymbols.ION_SYMBOL_TABLE;
import static com.amazon.ion.impl.IonImplUtils.UTF8_CHARSET;
import static com.amazon.ion.impl.IonImplUtils.addAllNonNull;
import static com.amazon.ion.impl.IonWriterFactory.DEFAULT_OPTIONS;
import static com.amazon.ion.impl.IonWriterFactory.makeWriter;
import static com.amazon.ion.impl.SystemValueIteratorImpl.makeSystemReader;
import static com.amazon.ion.impl.UnifiedSymbolTable.makeNewLocalSymbolTable;
import static com.amazon.ion.impl.UnifiedSymbolTable.makeNewSharedSymbolTable;
import static com.amazon.ion.impl.UnifiedSymbolTable.newSystemSymbolTable;
import static com.amazon.ion.util.IonStreamUtils.isIonBinary;
import static com.amazon.ion.util.IonTextUtils.printString;

import com.amazon.ion.ContainedValueException;
import com.amazon.ion.Decimal;
import com.amazon.ion.IonBlob;
import com.amazon.ion.IonBool;
import com.amazon.ion.IonCatalog;
import com.amazon.ion.IonClob;
import com.amazon.ion.IonContainer;
import com.amazon.ion.IonDatagram;
import com.amazon.ion.IonDecimal;
import com.amazon.ion.IonException;
import com.amazon.ion.IonFloat;
import com.amazon.ion.IonInt;
import com.amazon.ion.IonList;
import com.amazon.ion.IonLoader;
import com.amazon.ion.IonNull;
import com.amazon.ion.IonReader;
import com.amazon.ion.IonSequence;
import com.amazon.ion.IonSexp;
import com.amazon.ion.IonString;
import com.amazon.ion.IonStruct;
import com.amazon.ion.IonSymbol;
import com.amazon.ion.IonTextReader;
import com.amazon.ion.IonTimestamp;
import com.amazon.ion.IonType;
import com.amazon.ion.IonValue;
import com.amazon.ion.IonWriter;
import com.amazon.ion.SymbolTable;
import com.amazon.ion.SystemSymbolTable;
import com.amazon.ion.Timestamp;
import com.amazon.ion.UnexpectedEofException;
import com.amazon.ion.UnsupportedIonVersionException;
import com.amazon.ion.impl.IonBinary.BufferManager;
import com.amazon.ion.util.Printer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * The standard implementation of Ion.
 */
public final class IonSystemImpl
    implements IonSystemPrivate
{
    public static final int SYSTEM_VERSION = 1;

    private final UnifiedSymbolTable mySystemSymbols;

    /** Not null. */
    private final IonCatalog myCatalog;
    private final IonLoader myLoader;
    private final boolean myStreamCopyOptimized;


    /**
     * @param catalog must not be null.
     */
    public IonSystemImpl(IonCatalog catalog, boolean streamCopyOptimized)
    {
        assert catalog != null;
        myCatalog = catalog;
        myLoader = new LoaderImpl(this, myCatalog);
        mySystemSymbols = newSystemSymbolTable(SYSTEM_VERSION);
        myStreamCopyOptimized = streamCopyOptimized;
    }


    public final UnifiedSymbolTable getSystemSymbolTable()
    {
        return mySystemSymbols;
    }


    public UnifiedSymbolTable getSystemSymbolTable(String ionVersionId)
        throws UnsupportedIonVersionException
    {
        if (!ION_1_0.equals(ionVersionId)) {
            throw new UnsupportedIonVersionException(ionVersionId);
        }
        return getSystemSymbolTable();
    }


    public synchronized IonCatalog getCatalog()
    {
        return myCatalog;
    }


    public UnifiedSymbolTable newLocalSymbolTable(SymbolTable... imports)
    {
        UnifiedSymbolTable st =
            makeNewLocalSymbolTable(this, mySystemSymbols, imports);
        return st;
    }


    public UnifiedSymbolTable newSharedSymbolTable(IonStruct ionRep)
    {
        UnifiedSymbolTable st = makeNewSharedSymbolTable(ionRep);
        return st;
    }

    public UnifiedSymbolTable newSharedSymbolTable(IonReader reader)
    {
        UnifiedSymbolTable st = makeNewSharedSymbolTable(reader, false);
        return st;
    }

    public UnifiedSymbolTable newSharedSymbolTable(IonReader reader,
                                                   boolean isOnStruct)
    {
        UnifiedSymbolTable st = makeNewSharedSymbolTable(reader, isOnStruct);
        return st;
    }

    public UnifiedSymbolTable newSharedSymbolTable(String name,
                                                   int version,
                                                   Iterator<String> newSymbols,
                                                   SymbolTable... imports)
    {
        // TODO streamline to avoid making this collection
        ArrayList<String> syms = new ArrayList<String>();

        SymbolTable prior = null;
        if (version > 1)
        {
            int priorVersion = version - 1;
            prior = myCatalog.getTable(name, priorVersion);
            if (prior == null || prior.getVersion() != priorVersion)
            {
                String message =
                    "Catalog does not contain symbol table " +
                    printString(name) + " version " + priorVersion +
                    " required to create version " + version;
                throw new IonException(message);
            }
        }

        for (SymbolTable imported : imports)
        {
            addAllNonNull(syms, imported.iterateDeclaredSymbolNames());
        }

        addAllNonNull(syms, newSymbols);

        UnifiedSymbolTable st =
            makeNewSharedSymbolTable(name, version, prior, syms.iterator());

        return st;
    }


    public IonDatagramImpl newDatagram()
    {
        try
        {
            IonDatagramImpl dg =
                new IonDatagramImpl(this, getCatalog(), (IonReader) null);
            return dg;
        }
        catch (IOException e)
        {
            // Shouldn't happen actually
            throw new IonException(e);
        }
    }

    public IonDatagram newDatagram(IonValue initialChild)
    {
        IonDatagramImpl datagram = newDatagram();

        if (initialChild != null) {
            if (initialChild.getSystem() != this) {
                throw new IonException("this Ion system can't mix with instances from other system impl's");
            }

            // This is an API anomaly but it's documented so here we go.
            if (initialChild.getContainer() != null) {
                initialChild = clone(initialChild);
            }

            // This will fail if initialChild instanceof IonDatagram:
            datagram.add(initialChild);
        }

        assert datagram._system == this;
        return datagram;
    }

    public IonDatagram newDatagram(SymbolTable... imports)
    {
        // TODO this implementation is awkward.
        UnifiedSymbolTable lst = newLocalSymbolTable(imports);

        IonDatagramImpl datagram = newDatagram();
        datagram.setSymbolTable(lst);
        return datagram;
    }


    public IonLoader newLoader()
    {
        return new LoaderImpl(this, myCatalog);
    }

    public IonLoader newLoader(IonCatalog catalog)
    {
        if (catalog == null) catalog = getCatalog();
        return new LoaderImpl(this, catalog);
    }

    public IonLoader getLoader()
    {
        return myLoader;
    }


    //=========================================================================
    // Iterator creation


    public Iterator<IonValue> iterate(Reader reader)
    {
        // TODO optimize to use IonTextReader, but first that must truly stream
        // instead of requiring a full-stream buffer.
        // See https://jira2.amazon.com/browse/ION-31
        UserValueIterator userReader =
            new UserValueIterator(this, this.newLocalSymbolTable(), reader);
        userReader.setBufferToRecycle();
        return userReader;
    }

    /**
     * TODO Must correct ION-160 before exposing this or using from public API.
     * Unclear how to do buffer recycling since that's currently done by the
     * {@link UserValueIterator} an not by the system level.
     */
    protected SystemValueIterator systemIterate(Reader reader)
    {
        SystemValueIterator sysreader =
            makeSystemReader(this,
                             getCatalog(),
                             newLocalSymbolTable(),  // FIXME: should be null
                             reader);
        return sysreader;
    }

    public Iterator<IonValue> iterate(String ionText)
    {
        IonReader reader = newReader(ionText);
        return new IonIteratorImpl(this, reader);
    }

    public Iterator<IonValue> systemIterate(String ionText)
    {
        if (LoaderImpl.USE_NEW_READERS)
        {
            // TODO sadly this doesn't pass all tests yet
            // There's some strangeness with symbol tables
//            IonReader reader = newSystemReader(ionText);
//            return new IonIteratorImpl(this, reader);
        }

        SystemValueIterator reader = makeSystemReader(this, ionText);
        return reader;
    }


    public Iterator<IonValue> iterate(byte[] ionData)
    {
        SystemValueIterator systemReader =
            newLegacySystemReader(getCatalog(), ionData);
        UserValueIterator userReader = new UserValueIterator(systemReader);
        // Don't use buffer-clearing!
        return userReader;
    }


    public Iterator<IonValue> iterate(InputStream ionData)
    {
        return iterate(ionData, false);
    }

    /**
     * TODO Must correct ION-160 before exposing this or using from public API
     * If data is text, the resulting reader will NOT flush the buffer
     * and will accumulate memory!
     * See comment on {@link #systemIterate(Reader)} before adding to public APIs!
     */
    public Iterator<IonValue> systemIterate(InputStream ionData)
    {
        return iterate(ionData, true);
    }

    private Iterator<IonValue> iterate(InputStream ionData, boolean system)
    {
        if (ionData == null) throw new NullPointerException();

        SystemValueIterator systemReader;
        boolean binaryData;
        try
        {
            PushbackInputStream pushback = new PushbackInputStream(ionData, 8);
            binaryData = IonImplUtils.streamIsIonBinary(pushback);
            if (binaryData)
            {
                systemReader = newPagedBinarySystemReader(getCatalog(), pushback);
            }
            else
            {
                Reader reader = new InputStreamReader(pushback, UTF8_CHARSET);
                // This incrementally transcodes the whole stream into
                // a buffer. However, when system==false we wrap this with a
                // UserReader below, and then flip a switch to recycle the
                // buffer.
                systemReader = systemIterate(reader);
            }
        }
        catch (IOException e)
        {
            throw new IonException(e);
        }

        if (system) return systemReader;

        UserValueIterator userReader = new UserValueIterator(systemReader);
        if (!binaryData)
        {
            // This prevents us from accumulating all the transcoded data.
            userReader.setBufferToRecycle();
        }
        return userReader;
    }

    //=========================================================================
    // IonReader creation


    @SuppressWarnings("deprecation")
    public IonTextReader newReader(String ionText)
    {
        return new IonReaderTextUserX(this, null, ionText, 0, ionText.length());
    }

    @SuppressWarnings("deprecation")
    public IonTextReader newSystemReader(String ionText)
    {
        return new IonReaderTextSystemX(this, ionText, 0, ionText.length());
    }


    @SuppressWarnings("deprecation")
    public IonTextReader newSystemReader(Reader ionText)
    {
        return new IonReaderTextSystemX(this, ionText);
    }


    public IonReader newReader(byte[] ionData)
    {
        return newReader(ionData, 0, ionData.length);
    }

    public IonReader newSystemReader(byte[] ionData)
    {
        return newSystemReader(ionData, 0, ionData.length);
    }


    public IonReader newReader(byte[] ionData, int offset, int len)
    {
        boolean isBinary = isIonBinary(ionData, offset, len);
        if (isBinary)
        {
            return new IonReaderBinaryUserX(this, myCatalog, ionData, offset, len);
        }

        return new IonReaderTextUserX(this, myCatalog, ionData, offset, len);
    }


    public IonReader newSystemReader(byte[] ionData, int offset, int len)
    {
        boolean isBinary = isIonBinary(ionData, offset, len);
        if (isBinary)
        {
            return new IonReaderBinarySystemX(this, ionData, offset, len);
        }

        return new IonReaderTextSystemX(this, ionData, offset, len);
    }


    public IonReader newReader(InputStream ionData)
    {
        try
        {
            PushbackInputStream pushback =
                new PushbackInputStream(ionData, 8);
            boolean isBinary = IonImplUtils.streamIsIonBinary(pushback);

            if (isBinary)
            {
                return new IonReaderBinaryUserX(this, myCatalog, pushback);
            }

            Reader reader = new InputStreamReader(pushback, UTF8_CHARSET);
            return new IonReaderTextUserX(this, null, reader); // FIXME wrong catalog?
        }
        catch (IOException e)
        {
            throw new IonException(e);
        }
    }

    public IonReader newSystemReader(InputStream ionData)
    {
        try
        {
            PushbackInputStream pushback =
                new PushbackInputStream(ionData, 8);
            boolean isBinary = IonImplUtils.streamIsIonBinary(pushback);

            if (isBinary)
            {
                return new IonReaderBinarySystemX(this, pushback);
            }

            Reader reader = new InputStreamReader(pushback, UTF8_CHARSET);
            return new IonReaderTextSystemX(this, reader);
        }
        catch (IOException e)
        {
            throw new IonException(e);
        }
    }


    public IonReader newReader(IonValue value)
    {
        IonReader reader = new IonReaderTreeUserX(value, getCatalog());
        return reader;
    }

    public IonReader newSystemReader(IonValue value)
    {
        IonReader reader = new IonReaderTreeSystem(value);
        return reader;
    }


    //=========================================================================
    // IonWriter creation


    public IonWriter newWriter(IonContainer container)
    {
        IonWriter userWriter = IonWriterFactory.makeWriter(container);
        return userWriter;
    }

    public IonWriter newTextWriter(Appendable out)
    {
        return makeWriter(this, myCatalog, out, DEFAULT_OPTIONS);
    }

    public IonWriter newTextWriter(Appendable out, boolean pretty)
    {
        $PrivateTextOptions options =
            new $PrivateTextOptions(pretty /* prettyPrint */,
                                    true /* printAscii */,
                                    true /* filterOutSymbolTables */);
        return makeWriter(this, myCatalog, out, options);
    }

    public IonWriter newTextWriter(Appendable out, $PrivateTextOptions options)
    {
        return makeWriter(this, myCatalog, out, options);
    }

    public IonWriter newTextWriter(Appendable out, SymbolTable... imports)
    {
        return makeWriter(this, myCatalog, out, DEFAULT_OPTIONS, imports);
    }

    public IonWriter newTextWriter(Appendable out,
                                   $PrivateTextOptions options,
                                   SymbolTable... imports)
    {
        return makeWriter(this, myCatalog, out, options, imports);
    }

    public IonWriter newTextWriter(OutputStream out)
    {
        return makeWriter(this, myCatalog, out, DEFAULT_OPTIONS);
    }

    public IonWriter newTextWriter(OutputStream out,
                                   $PrivateTextOptions options)
    {
        return makeWriter(this, myCatalog, out, options);
    }

    public IonWriter newTextWriter(OutputStream out, SymbolTable... imports)
    {
        return makeWriter(this, myCatalog, out, DEFAULT_OPTIONS, imports);
    }

    public IonWriter newTextWriter(OutputStream out,
                                   $PrivateTextOptions options,
                                   SymbolTable... imports)
    {
        return makeWriter(this, myCatalog, out, options, imports);
    }


    // TODO also Utf8AsAscii flag - this should be extended to be
    //      printer options which can pick up tab width, JSON format
    //      choices and other text related options.
    public IonWriter newTextWriter(OutputStream out, boolean pretty)
    {
        // return new IonTextWriter(out, pretty);
        $PrivateTextOptions options =
            new $PrivateTextOptions(pretty,
                                    true /* printAscii */,
                                    true /* filterOutSymbolTables */);
        IonWriter userWriter = newTextWriter(out, options);
        return userWriter;
    }

    @Deprecated
    public com.amazon.ion.IonBinaryWriter newBinaryWriter()
    {
        IonWriterBinaryCompatibility.User writer =
            new IonWriterBinaryCompatibility.User(this, myCatalog,
                                                  myStreamCopyOptimized);
        return writer;
    }

    @Deprecated
    public com.amazon.ion.IonBinaryWriter newBinaryWriter(SymbolTable... imports)
    {
        UnifiedSymbolTable lst = newLocalSymbolTable(imports);
        IonWriterBinaryCompatibility.User user_writer =
            new IonWriterBinaryCompatibility.User(this, myCatalog,
                                                  myStreamCopyOptimized);
        try {
            user_writer.setSymbolTable(lst);
        }
        catch (IOException e) {
            throw new IonException(e);
        }
        return user_writer;
    }

    public IonWriter newBinaryWriter(OutputStream out, SymbolTable... imports)
    {
        IonWriterUserBinary writer =
            IonWriterFactory.newBinaryWriter(this, getCatalog(),
                                             myStreamCopyOptimized,
                                             out, imports);
        return writer;
    }


    /**
     * @param container must not be null.
     */
    public IonWriter newTreeWriter(IonContainer container)
    {
        IonWriter writer = IonWriterFactory.makeWriter(container);
        return writer;
    }

    /**
     * @param container must not be null.
     */
    public IonWriter newTreeSystemWriter(IonContainer container)
    {
        IonWriter system_writer = IonWriterFactory.makeSystemWriter(container);
        return system_writer;
    }



    //=========================================================================
    // Internal SystemReader creation


    /**
     * Creates a new reader, wrapping an array of text or binary data.
     *
     * @param catalog The catalog to use.
     * @param ionData may be (UTF-8) text or binary.
     * <em>This method assumes ownership of the array</em> and may modify it at
     * will.
     *
     * @throws NullPointerException if <code>ionData</code> is null.
     */
    public SystemValueIterator newLegacySystemReader(IonCatalog catalog,
                                                     byte[] ionData)
    {
        if (catalog == null) catalog = getCatalog();
        boolean isBinary = isIonBinary(ionData);

        SystemValueIterator sysReader;
        if (isBinary) {
            sysReader = newBinarySystemReader(catalog, ionData);
        }
        else {
            sysReader = newTextSystemReader(catalog, ionData);
        }

        return sysReader;
    }


    /**
     * Creates a new reader, wrapping an array of binary data.
     *
     * @param catalog the catalog to use.
     * @param ionBinary must be Ion binary data, not text.
     * <em>This method assumes ownership of the array</em> and may modify it at
     * will.
     *
     * @throws NullPointerException if <code>ionBinary</code> is null.
     */
    private SystemValueIterator newBinarySystemReader(IonCatalog catalog,
                                                      byte[] ionBinary)
    {
        if (catalog == null) catalog = getCatalog();
        BlockedBuffer bb = new BlockedBuffer(ionBinary);
        BufferManager buffer = new BufferManager(bb);
        //return new SystemReader(this, catalog, buffer);
        SystemValueIterator reader = makeSystemReader(this, catalog, buffer);
        return reader;
    }


    /**
     * Creates a new reader, wrapping bytes holding UTF-8 text.
     *
     * @param catalog the catalog to use.
     * @param ionText must be UTF-8 encoded Ion text data, not binary.
     * <em>This method assumes ownership of the array</em> and may modify it at
     * will.
     *
     * @throws NullPointerException if <code>ionText</code> is null.
     */
    private SystemValueIterator newTextSystemReader(IonCatalog catalog,
                                                    byte[] ionText)
    {
        if (catalog == null) catalog = getCatalog();
        ByteArrayInputStream stream = new ByteArrayInputStream(ionText);
        Reader reader = new InputStreamReader(stream, UTF8_CHARSET);

        // return new SystemReader(this, catalog, reader);
        SystemValueIterator sysreader = makeSystemReader(this, catalog, reader);
        return sysreader;
    }


    public SystemValueIterator newBinarySystemReader(IonCatalog catalog,
                                                     InputStream ionBinary)
        throws IOException
    {
        if (catalog == null) catalog = getCatalog();
        BufferManager buffer = new BufferManager(ionBinary);
        //return new SystemReader(this, catalog, buffer);
        SystemValueIterator reader = makeSystemReader(this, catalog, buffer);
        return reader;
    }

    public SystemValueIterator newPagedBinarySystemReader(IonCatalog catalog,
                                                          InputStream ionBinary)
        throws IOException
    {
        if (catalog == null) catalog = getCatalog();
        SystemValueIterator reader = makeSystemReader(this, catalog, ionBinary);
        return reader;
    }

    //-------------------------------------------------------------------------
    // System Elements

    IonSymbolImpl newSystemIdSymbol(String systemId)
    {
        assert textIsSystemId(systemId);

        IonSymbolImpl ivm = (IonSymbolImpl) newSymbol(systemId);
        blessSystemIdSymbol(ivm);
        return ivm;
    }

    final void blessSystemIdSymbol(IonSymbolImpl systemId)
    {
        // TODO what if the symbol already has a symtab?
        // TODO what if the symbol already has a different system?

        SymbolTable symtab = systemId.getSymbolTable();

        // generally we don't want the IVM holding the symbol
        // table, but if it has an annotation it's another matter
        if (systemId.getTypeAnnotations().length == 0) {
            symtab = getSystemSymbolTable(systemId.stringValue());
        }
        else if (UnifiedSymbolTable.isLocalTable(symtab) == false) {
            if (symtab == null) {
                symtab = this.mySystemSymbols;
            }
            symtab = UnifiedSymbolTable.makeNewLocalSymbolTable(this, symtab);
        }

        systemId.setSymbolTable(symtab);  // This clears the sid
        systemId.setIsIonVersionMarker(true);
        systemId.setDirty();
        assert systemId.getSymbolId() == 2;
    }

    /**
     * Checks the value to see if it is an unannotated symbol and has the
     * form {@code $ion_DDD_DDD} where DDD is 1 or more decimal
     * digits.  This includes the current value of {@code $ion_1_0}
     * which is really the only valid system id today, but
     * there may be more later.
     */
    final boolean valueIsSystemId(IonValue value)
    {
        if (value instanceof IonSymbol
            && ! value.isNullValue()
            && value.getTypeAnnotations().length == 0)
        {
            IonSymbol symbol = (IonSymbol) value;
            int sid = symbol.getSymbolId();
            if (sid == ION_1_0_SID)
            {
                return true;
            }
            else if (sid < 1 || sid > SystemSymbolTable.ION_1_0_MAX_ID) {
                String image = symbol.stringValue();
                return textIsSystemId(image);
            }
        }
        return false;
    }

    private final boolean textIsSystemId(String image)
    {
        if (ION_1_0.equals(image))
        {
            return true;
        }
        if (!image.startsWith(ION)) {
            return false;
        }
        // now we see if the rest of the symbol is _DDD_DDD
        int underscore1 = ION.length();
        int underscore2 = image.indexOf('_', underscore1 + 1);
        if (underscore2 < 0)
        {
            return false;
        }
        if (!isUnderscoreAndDigits(image, underscore1, underscore2))
        {
            return false;
        }
        if (!isUnderscoreAndDigits(image, underscore2, image.length()))
        {
            return false;
        }
        return true;
    }


    private boolean isUnderscoreAndDigits(String image, int firstChar,
                                          int lastChar)
    {
        // you have to have enough characters for the underscore and
        // at least 1 digit
        if (lastChar - firstChar < 2) return false;

        // make sure the first character is the underscore
        if (image.charAt(firstChar) != '_') return false;

        // make sure all the remaining characters are digits
        for (int ii = firstChar + 1; ii < lastChar; ii++) {
            if (!Character.isDigit(image.charAt(ii))) return false;
        }

        // it must be "_ddd" then
        return true;
    }

    public static final boolean valueIsLocalSymbolTable(IonValue value)
    {
        return (value instanceof IonStruct
                && value.hasTypeAnnotation(ION_SYMBOL_TABLE));
    }

    public final boolean valueIsSharedSymbolTable(IonValue value)
    {
        return (value instanceof IonStruct
                && value.hasTypeAnnotation(ION_SHARED_SYMBOL_TABLE));
    }


    //-------------------------------------------------------------------------
    // DOM creation

    /**
     * Helper for other overloads, not useful as public API.
     */

    private IonValue singleValue(Iterator<IonValue> iterator)
    {
        if (iterator.hasNext())
        {
            IonValue value = iterator.next();
            if (! iterator.hasNext())
            {
                return value;
            }
        }

        throw new IonException("not a single value");
    }

    /**
     * Helper for other overloads, not useful as public API.
     */
    private IonValue singleValue(IonReader reader)
    {
        if (reader.next() == null)
        {
            throw new UnexpectedEofException("no value found on input stream");
        }

        IonValue value = newValue(reader);
        if (reader.next() == null)
        {
            return value;
        }

        throw new IonException("not a single value");
    }


    public IonValue singleValue(String ionText)
    {
        return singleValue(newReader(ionText));
    }

    public IonValue singleValue(byte[] ionData)
    {
        return singleValue(newReader(ionData));
    }


    /**
     * Does not call {@link IonReader#next()}.
     *
     * @return a new value object, not null.
     */
    public IonValue newValue(IonReader reader)
    {
        IonType t = reader.getType();
        if (t == null) {
            throw new IonException("No value available");
        }

        // TODO streamline
        IonList container = newEmptyList();
        IonWriter writer = newWriter(container);
        try
        {
            writer.writeValue(reader);
        }
        catch (IOException e)
        {
            throw new IonException("Unexpected exception writing to DOM", e);
        }

        IonValue v = container.remove(0);
        assert v != null;
        return v;
    }


    public IonBlob newNullBlob()
    {
        return new IonBlobImpl(this);
    }

    public IonBlob newBlob(byte[] value)
    {
        IonBlob result = new IonBlobImpl(this);
        result.setBytes(value);
        return result;
    }

    public IonBlob newBlob(byte[] value, int offset, int length)
    {
        IonBlob result = new IonBlobImpl(this);
        result.setBytes(value, offset, length);
        return result;
    }


    public IonBool newNullBool()
    {
        return new IonBoolImpl(this);
    }

    public IonBool newBool(boolean value)
    {
        IonBoolImpl result = new IonBoolImpl(this);
        result.setValue(value);
        return result;
    }

    public IonBool newBool(Boolean value)
    {
        IonBoolImpl result = new IonBoolImpl(this);
        result.setValue(value);
        return result;
    }


    public IonClob newNullClob()
    {
        return new IonClobImpl(this);
    }

    public IonClob newClob(byte[] value)
    {
        IonClob result = new IonClobImpl(this);
        result.setBytes(value);
        return result;
    }

    public IonClob newClob(byte[] value, int offset, int length)
    {
        IonClob result = new IonClobImpl(this);
        result.setBytes(value, offset, length);
        return result;
    }


    public IonDecimal newNullDecimal()
    {
        return new IonDecimalImpl(this);
    }

    public IonDecimal newDecimal(long value)
    {
        return new IonDecimalImpl(this, Decimal.valueOf(value));
    }

    public IonDecimal newDecimal(double value)
    {
        return new IonDecimalImpl(this, Decimal.valueOf(value));
    }

    public IonDecimal newDecimal(BigInteger value)
    {
        return new IonDecimalImpl(this, Decimal.valueOf(value));
    }

    public IonDecimal newDecimal(BigDecimal value)
    {
        return new IonDecimalImpl(this, value);
    }


    public IonFloat newNullFloat()
    {
        return new IonFloatImpl(this);
    }

    public IonFloat newFloat(long value)
    {
        return new IonFloatImpl(this, new Double(value));
    }

    public IonFloat newFloat(double value)
    {
        return new IonFloatImpl(this, new Double(value));
    }


    public IonInt newNullInt()
    {
        return new IonIntImpl(this);
    }

    public IonInt newInt(int content)
    {
        IonIntImpl result = new IonIntImpl(this);
        result.setValue(content);
        return result;
    }

    public IonInt newInt(long content)
    {
        IonIntImpl result = new IonIntImpl(this);
        result.setValue(content);
        return result;
    }

    public IonInt newInt(Number content)
    {
        IonIntImpl result = new IonIntImpl(this);
        result.setValue(content);
        return result;
    }


    public IonList newNullList()
    {
        return new IonListImpl(this);
    }

    public IonList newEmptyList()
    {
        return new IonListImpl(this, false);
    }

    public IonList newList(Collection<? extends IonValue> elements)
        throws ContainedValueException, NullPointerException
    {
        return new IonListImpl(this, elements);
    }

    public IonList newList(IonSequence child)
        throws ContainedValueException, NullPointerException
    {
        return new IonListImpl(this, Collections.singletonList(child));
    }

    public IonList newList(IonValue... elements)
        throws ContainedValueException, NullPointerException
    {
        List<IonValue> e = (elements == null ? null : Arrays.asList(elements));
        return new IonListImpl(this, e);
    }

    public IonList newList(int[] elements)
    {
        ArrayList<IonInt> e = newInts(elements);
        return newList(e);
    }

    public IonList newList(long[] elements)
    {
        ArrayList<IonInt> e = newInts(elements);
        return newList(e);
    }


    public IonNull newNull()
    {
        return new IonNullImpl(this);
    }


    public IonValue newNull(IonType type)
    {
        switch (type)
        {
            case NULL:          return newNull();
            case BOOL:          return newNullBool();
            case INT:           return newNullInt();
            case FLOAT:         return newNullFloat();
            case DECIMAL:       return newNullDecimal();
            case TIMESTAMP:     return newNullTimestamp();
            case SYMBOL:        return newNullSymbol();
            case STRING:        return newNullString();
            case CLOB:          return newNullClob();
            case BLOB:          return newNullBlob();
            case LIST:          return newNullList();
            case SEXP:          return newNullSexp();
            case STRUCT:        return newNullStruct();
            default:
                throw new IllegalArgumentException();
        }
    }


    public IonSexp newNullSexp()
    {
        return new IonSexpImpl(this);
    }

    public IonSexp newEmptySexp()
    {
        return new IonSexpImpl(this, false);
    }

    public IonSexp newSexp(Collection<? extends IonValue> elements)
        throws ContainedValueException, NullPointerException
    {
        return new IonSexpImpl(this, elements);
    }

    public IonSexp newSexp(IonSequence child)
        throws ContainedValueException, NullPointerException
    {
        return new IonSexpImpl(this, Collections.singletonList(child));
    }


    public IonSexp newSexp(IonValue... elements)
        throws ContainedValueException, NullPointerException
    {
        List<IonValue> e = (elements == null ? null : Arrays.asList(elements));
        return new IonSexpImpl(this, e);
    }

    public IonSexp newSexp(int[] elements)
    {
        ArrayList<IonInt> e = newInts(elements);
        return newSexp(e);
    }

    public IonSexp newSexp(long[] elements)
    {
        ArrayList<IonInt> e = newInts(elements);
        return newSexp(e);
    }


    public IonString newNullString()
    {
        return new IonStringImpl(this);
    }

    public IonString newString(String content)
    {
        IonStringImpl result = new IonStringImpl(this);
        result.setValue(content);
        return result;
    }


    public IonStruct newNullStruct()
    {
        return new IonStructImpl(this);
    }

    public IonStruct newEmptyStruct()
    {
        IonStructImpl result = new IonStructImpl(this);
        result.clear();
        return result;
    }


    public IonSymbol newNullSymbol()
    {
        return new IonSymbolImpl(this);
    }

    public IonSymbol newSymbol(String name)
    {
        return new IonSymbolImpl(this, name);
    }


    public IonTimestamp newNullTimestamp()
    {
        return new IonTimestampImpl(this);
    }

    public IonTimestamp newTimestamp(Timestamp timestamp)
    {
        IonTimestamp result = newNullTimestamp();
        result.setValue(timestamp);
        return result;
    }

    public IonTimestamp newUtcTimestampFromMillis(long millis)
    {
        IonTimestampImpl result = new IonTimestampImpl(this);
        result.setMillisUtc(millis);
        return result;
    }

    public IonTimestamp newUtcTimestamp(Date value)
    {
        IonTimestampImpl result = new IonTimestampImpl(this);
        if (value != null)
        {
            result.setMillisUtc(value.getTime());
        }
        return result;
    }

    public IonTimestamp newCurrentUtcTimestamp()
    {
        IonTimestampImpl result = new IonTimestampImpl(this);
        result.setCurrentTimeUtc();
        return result;
    }


    @SuppressWarnings("unchecked")
    public <T extends IonValue> T clone(T value)
    {
        // Use "fast clone" when the system is the same.
        if (value.getSystem() == this)
        {
            return (T) value.clone();
        }

        if (value instanceof IonDatagram)
        {
            byte[] data = ((IonDatagram)value).getBytes();

            // TODO This can probably be optimized further.
            return (T) new IonDatagramImpl(this, this.getCatalog(), data);
        }

        StringBuilder buffer = new StringBuilder();
        Printer printer = new Printer();
        try
        {
            printer.print(value, buffer);
        }
        catch (IOException e)
        {
            throw new IonException(e);
        }

        String text = buffer.toString();
        return (T) singleValue(text);
    }


    //=========================================================================
    // Helpers

    private ArrayList<IonInt> newInts(int[] elements)
    {
        ArrayList<IonInt> e = null;

        if (elements != null)
        {
            e = new ArrayList<IonInt>(elements.length);
            for (int i = 0; i < elements.length; i++)
            {
                int value = elements[i];
                e.add(newInt(value));
            }
        }

        return e;
    }

    private ArrayList<IonInt> newInts(long[] elements)
    {
        ArrayList<IonInt> e = null;

        if (elements != null)
        {
            e = new ArrayList<IonInt>(elements.length);
            for (int i = 0; i < elements.length; i++)
            {
                long value = elements[i];
                e.add(newInt(value));
            }
        }

        return e;
    }

}
