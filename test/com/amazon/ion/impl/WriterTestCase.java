// Copyright (c) 2009 Amazon.com, Inc.  All rights reserved.

package com.amazon.ion.impl;

import static com.amazon.ion.Symtabs.FRED_MAX_IDS;
import static com.amazon.ion.Symtabs.GINGER_MAX_IDS;
import static com.amazon.ion.SystemSymbolTable.ION_1_0_MAX_ID;

import com.amazon.ion.IonDatagram;
import com.amazon.ion.IonTestCase;
import com.amazon.ion.IonValue;
import com.amazon.ion.IonWriter;
import com.amazon.ion.SymbolTable;
import com.amazon.ion.Symtabs;

/**
 *
 */
public abstract class WriterTestCase
    extends IonTestCase
{
    protected abstract IonWriter makeWriter(SymbolTable... imports)
        throws Exception;

    protected abstract byte[] outputByteArray()
        throws Exception;


    public void testWritingWithImports()
        throws Exception
    {
        final int FRED_ID_OFFSET   = ION_1_0_MAX_ID;
        final int GINGER_ID_OFFSET = FRED_ID_OFFSET + FRED_MAX_IDS[1];
        final int LOCAL_ID_OFFSET  = GINGER_ID_OFFSET + GINGER_MAX_IDS[1];

        SymbolTable fred1   = Symtabs.register("fred",   1, catalog());
        SymbolTable ginger1 = Symtabs.register("ginger", 1, catalog());

        IonWriter writer = makeWriter(fred1, ginger1);
        writer.writeSymbol("fred_2");
        writer.writeSymbol("g1");
        writer.writeSymbol("localSym");

        byte[] bytes = outputByteArray();
        IonDatagram dg = loader().load(bytes);

        assertEquals(5, dg.systemSize());

        IonValue f2sym = dg.systemGet(2);
        IonValue g1sym = dg.systemGet(3);
        IonValue local = dg.systemGet(4);

        checkSymbol("fred_2",   FRED_ID_OFFSET + 2,   f2sym);
        checkSymbol("g1",       GINGER_ID_OFFSET + 1, g1sym);
        checkSymbol("localSym", LOCAL_ID_OFFSET + 1,  local);

        SymbolTable symtab = f2sym.getSymbolTable();
        assertSame(symtab, g1sym.getSymbolTable());
        SymbolTable[] importedTables = symtab.getImportedTables();
        assertEquals(2, importedTables.length);
        assertSame(fred1, importedTables[0]);
        assertSame(ginger1, importedTables[1]);
    }

    // TODO test stepOut() when at top-level
}
