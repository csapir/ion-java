/*
 * Copyright (c) 2007-2008 Amazon.com, Inc.  All rights reserved.
 */

package com.amazon.ion.impl;

import static com.amazon.ion.SystemSymbolTable.ION_1_0;
import static com.amazon.ion.SystemSymbolTable.ION_1_0_MAX_ID;
import static com.amazon.ion.SystemSymbolTable.ION_SYMBOL_TABLE;
import static com.amazon.ion.impl.UnifiedSymbolTable.ION_SHARED_SYMBOL_TABLE;

import com.amazon.ion.IonCatalog;
import com.amazon.ion.IonDatagram;
import com.amazon.ion.IonException;
import com.amazon.ion.IonInt;
import com.amazon.ion.IonList;
import com.amazon.ion.IonLoader;
import com.amazon.ion.IonReader;
import com.amazon.ion.IonSexp;
import com.amazon.ion.IonStruct;
import com.amazon.ion.IonSystem;
import com.amazon.ion.IonTestCase;
import com.amazon.ion.IonText;
import com.amazon.ion.IonValue;
import com.amazon.ion.SymbolTable;
import com.amazon.ion.SystemSymbolTable;
import com.amazon.ion.system.SimpleCatalog;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Iterator;

/**
 *
 */
public class SymbolTableTest
    extends IonTestCase
{
    @Deprecated
    public final String SymbolTablePrefix = SystemSymbolTable.ION_SYMBOL_TABLE + "::";
    public final String LocalSymbolTablePrefix = ION_SYMBOL_TABLE + "::";
    public final String SharedSymbolTablePrefix = ION_SHARED_SYMBOL_TABLE + "::";


    public final static int IMPORTED_1_MAX_ID = 2;
    public final static int IMPORTED_2_MAX_ID = 4;
    public final static int IMPORTED_3_MAX_ID = 5;


    public SymbolTable loadSharedSymtab(String serializedSymbolTable)
    {
        String text = "$ion_1_0 " + serializedSymbolTable;
        IonStruct st = (IonStruct) oneValue(text);
        SymbolTable shared = system().newSharedSymbolTable(st);
        assertTrue(shared.isSharedTable());
        return shared;
    }

    public SymbolTable registerSharedSymtab(String serializedSymbolTable)
    {
        SymbolTable shared = loadSharedSymtab(serializedSymbolTable);
        system().getCatalog().putTable(shared);
        return shared;
    }

    public SymbolTable registerImportedV1()
    {
        String importingText =
            SharedSymbolTablePrefix +
            "{" +
            "  name:'''imported''', version:1," +
            "  symbols:['''imported 1''', '''imported 2''']" +
            "}";
        SymbolTable shared = registerSharedSymtab(importingText);
        assertEquals(IMPORTED_1_MAX_ID, shared.getMaxId());

//        system.getCatalog().putTable(shared);

        SymbolTable importedTable =
            system().getCatalog().getTable("imported", 1);
        assertSame(shared, importedTable);
        assertEquals(IMPORTED_1_MAX_ID, importedTable.getMaxId());

        return importedTable;
    }

    public SymbolTable registerImportedV2()
    {
        IonSystem system = system();
        String importingText =
            "$ion_1_0\n" +
            "$ion_symbol_table::{" +
            "  name:'''imported''', version:2," +
            "  symbols:{" +
            "    $1:'''imported 1'''," +
            "    $2:'''imported 2'''," +
            "    $3:'''fred3'''," +
            "    $4:'''fred4'''," +
            "  }" +
            "}\n" +
            "null";
        loader().load(importingText);

        SymbolTable importedTable =
            system.getCatalog().getTable("imported", 2);
        assertEquals(IMPORTED_2_MAX_ID, importedTable.getMaxId());

        return importedTable;
    }

    public SymbolTable registerImportedV3()
    {
        IonSystem system = system();
        String importingText =
            "$ion_1_0\n" +
            "$ion_symbol_table::{" +
            "  name:'''imported''', version:3," +
            "  symbols:{" +
            "    $1:'''imported 1'''," +
            // Removed symbol     imported 2
            "    $3:'''fred3'''," +
            "    $4:'''fred4'''," +
            "    $5:'''fred5'''," +
            "  }" +
            "}\n" +
            "null";
        oneValue(importingText);

        SymbolTable importedTable =
            system.getCatalog().getTable("imported", 3);
        assertEquals(IMPORTED_3_MAX_ID, importedTable.getMaxId());

        return importedTable;
    }


    //=========================================================================
    // Test cases

    public void testLocalTable()
    {
        String text =
            LocalSymbolTablePrefix +
            "{" +
            "  symbols:{ $100:\"foo\"," +
            "            $101:\"bar\"}," +
            "}\n" +
            "null";

        SymbolTable symbolTable = oneValue(text).getSymbolTable();
        checkLocalTable(symbolTable);

        if (false)
            assertEquals(2, symbolTable.size()); // FIXME correct size() impl

        checkSymbol("foo", 100, symbolTable);
        checkSymbol("bar", 101, symbolTable);

        assertEquals(-1, symbolTable.findSymbol("not there"));
        assertEquals("$33", symbolTable.findSymbol(33));
    }


    public void testLocalTableReplacement()
    {
        String text =
            LocalSymbolTablePrefix +
            "{" +
            "  symbols:{ $100:\"foo\"," +
            "            $101:\"bar\"}," +
            "}\n" +
            "bar foo\n" +
            LocalSymbolTablePrefix +
            "{" +
            "  symbols:{ $13:\"foo\"}," +
            "}\n" +
            "bar foo";

//        IonReader scanner = system().newReader(text);
        Iterator<IonValue> scanner = system().iterate(text);

        IonValue value = scanner.next();
        checkSymbol("bar", 101, value);

        SymbolTable table1 = //scanner.getLocalSymbolTable();
            value.getSymbolTable();
        checkLocalTable(table1);

        value = scanner.next();
        checkSymbol("foo", 100, value);

        value = scanner.next();
        checkSymbol("bar", 14, value);

        SymbolTable table2 = //scanner.getLocalSymbolTable();
            value.getSymbolTable();
        checkLocalTable(table2);
        assertNotSame(table1, table2);
        assertEquals(14, table2.getMaxId());

        value = scanner.next();
        checkSymbol("foo", 13, value);
        assertEquals(14, table2.getMaxId());

//        assertSame(table2, scanner.getLocalSymbolTable());
    }


    public void testLocalTableResetting()
        throws Exception
    {
        String text =
            LocalSymbolTablePrefix +
            "{" +
            "  symbols:{ $100:\"foo\"," +
            "            $101:\"bar\"}," +
            "}\n" +
            "bar foo\n" +
            "$ion_1_0\n" +
            "1 bar foo";

//        IonReader scanner = system().newReader(text);
//        testLocalTableResetting(scanner);

        Iterator<IonValue> iterator = system().iterate(text);
        testLocalTableResetting(iterator);

        IonLoader loader = loader();
        IonDatagram datagram = loader.load(text);

        testLocalTableResetting(datagram.iterator());

        // FIXME this fails because the (second) local table isn't encoded.
        datagram = loader.load(datagram.toBytes());
        testLocalTableResetting(datagram.iterator());
    }


    public void testLocalTableResetting(Iterator<IonValue> values)
    {
//        IonReader scanner = ((values instanceof IonReader)
//                              ? (IonReader) values
//                              : null);

        IonValue value = values.next();
        checkSymbol("bar", 101, value);

        SymbolTable table1 = value.getSymbolTable();
        checkLocalTable(table1);
//        if (scanner != null) {
//            assertSame(table1, scanner.getLocalSymbolTable());
//        }

        value = values.next();
        checkSymbol("foo", 100, value);
        assertSame(table1, value.getSymbolTable());
//        if (scanner != null) {
//            assertSame(table1, scanner.getLocalSymbolTable());
//        }

        // Symbol table changes here

        // Make sure the symtab doesn't change until we call next()
//        assertTrue(values.hasNext());
//        if (scanner != null) {
//            assertSame(table1, scanner.getLocalSymbolTable());
//        }

        value = values.next();
        checkInt(1, value);

        value = values.next();
        checkSymbol("bar", value);

        SymbolTable table2 = value.getSymbolTable();
        checkLocalTable(table2);
        assertNotSame(table1, table2);
//        assertEquals(14, table2.getMaxId());  // We don't know the new sid

        value = values.next();
        checkSymbol("foo", value);
//        assertEquals(14, table2.getMaxId());

        assertSame(table2, value.getSymbolTable());
    }


    /**
     * Attempts to override system sids are ignored.
     */
    public void testOverridingSystemSymbolId()
    {
        int nameSid =
            system().getSystemSymbolTable("$ion_1_0").findSymbol("name");

        String importingText =
            LocalSymbolTablePrefix +
            "{" +
            "  symbols:{" +
            "    $" + nameSid + ":'''shadow'''," +
            "    $25:'''local25'''," +
            "  }," +
            "}\n" +
            "null";

        Iterator<IonValue> scanner = system().iterate(importingText);
        IonValue v = scanner.next();
        SymbolTable symtab = v.getSymbolTable();
        assertTrue(symtab.isLocalTable());
        assertEquals(-1, symtab.findSymbol("shadow"));
        assertEquals(25, symtab.findSymbol("local25"));
    }


    public void testOverridingImportedSymbolId()
    {
        registerImportedV1();

        int shadowId = ION_1_0_MAX_ID + 1;

        String importingText =
            "$ion_1_0 "+
            LocalSymbolTablePrefix +
            "{" +
            "  imports:[{name:'''imported''', version:1, max_id:2}]," +
            "  symbols:{" +
            "    $" + shadowId + ":'''shadow'''," +
            "    $25:'''local 25'''," +
            "  }," +
            "}\n" +
            "'local 25'\n" +
            "'imported 1'\n" +
            "shadow\n" +
            "$" + shadowId;

        Iterator<IonValue> scanner = system().iterate(importingText);

        IonValue value = scanner.next();
        SymbolTable symtab = value.getSymbolTable();
        checkLocalTable(symtab);

        checkSymbol("local 25", 25, value);

        assertNull(symtab.findKnownSymbol(26));

        value = scanner.next();
        checkSymbol("imported 1", shadowId, value);

        value = scanner.next();
        checkSymbol("shadow", 26, value);

        // Here the input text is $NNN  but it comes back correctly.
        value = scanner.next();
        checkSymbol("imported 1", shadowId, value);
    }


    public void testInjectingMaxIdIntoImport()
    {
        SymbolTable importedTable = registerImportedV1();

        String text =
            LocalSymbolTablePrefix +
            "{" +
            "  symbols:{ $100:\"dates\",\n" +  // TODO cleanup, unneccessary
            "            $101:\"whenDate\"},\n" +
            "  imports:[{name:'''imported''',version:1}],\n" +
            "}\n" +
            "null";
        IonDatagram dg = loader().load(text);

        SymbolTable symbolTable = dg.get(0).getSymbolTable();
        checkLocalTable(symbolTable);

        SymbolTable[] imported = symbolTable.getImportedTables();
        assertEquals(1, imported.length);
        assertSame(importedTable, imported[0]);

        // Check that the encoded table has max_id on import
        byte[] binary = dg.toBytes();
        dg = loader().load(binary);
        IonStruct symtabStruct = (IonStruct) dg.systemGet(1);
        IonList imports = (IonList) symtabStruct.get("imports");
        IonStruct importStruct = (IonStruct) imports.get(0);
        checkString("imported", importStruct.get("name"));

        IonValue maxIdValue = importStruct.get("max_id");
        assertNotNull("max_id wasn't injected into import", maxIdValue);
        checkInt(IMPORTED_1_MAX_ID, maxIdValue);
    }


    public void testLocalTableWithMissingImport()
    {
        final int import1id = ION_1_0_MAX_ID + 1;
        final int import2id = ION_1_0_MAX_ID + 2;

        final int local1id = ION_1_0_MAX_ID + IMPORTED_1_MAX_ID + 1;
        final int local2id = local1id + 1;

        SymbolTable importedTable = registerImportedV1();

        String text =
            LocalSymbolTablePrefix +
            "{" +
            "  symbols:{ $" + local1id + ":\"local1\"," +
            "            $" + local2id + ":\"local2\"}," +
            "  imports:[{name:\"imported\", version:1," +
            "            max_id:" + IMPORTED_1_MAX_ID + "}]," +
            "}\n" +
            "local1 local2 'imported 1' 'imported 2'";
        byte[] binary = encode(text);

        // Remove the imported table before decoding the binary.
        SimpleCatalog catalog = (SimpleCatalog) system().getCatalog();
        assertSame(importedTable, catalog.removeTable("imported", 1));

        IonDatagram dg = loader().load(binary);
        checkSymbol("local1", local1id, dg.get(0));
        checkSymbol("local2", local2id, dg.get(1));
        checkSymbol("$" + import1id, import1id, dg.get(2));
        checkSymbol("$" + import2id, import2id, dg.get(3));

        SymbolTable st = dg.get(3).getSymbolTable();
        checkLocalTable(st);

        SymbolTable dummy = findImportedTable(st, "imported");
        assertEquals(1, dummy.getVersion());
        assertEquals(IMPORTED_1_MAX_ID, dummy.getMaxId());
        assertEquals(-1, dummy.findSymbol("imported 1"));
        assertEquals(-1, dummy.findSymbol("imported 2"));

        assertEquals(-1, st.findSymbol("imported 1"));
        assertEquals(-1, st.findSymbol("imported 2"));
        assertEquals(-1, st.findSymbol("unknown"));
    }


    /**
     * Import v2 but catalog has v1.
     */
    public void testLocalTableWithLesserImport()
    {
        final int import1id = ION_1_0_MAX_ID + 1;
        final int import2id = ION_1_0_MAX_ID + 2;
        final int fred3id   = ION_1_0_MAX_ID + 3;

        final int local1id = ION_1_0_MAX_ID + IMPORTED_2_MAX_ID + 1;
        final int local2id = local1id + 1;

        registerImportedV1();
        registerImportedV2();

        String text =
            LocalSymbolTablePrefix +
            "{" +
            "  symbols:{ $" + local1id + ":\"local1\"," +
            "            $" + local2id + ":\"local2\"}," +
            "  imports:[{name:\"imported\", version:2,}]," +
            "}\n" +
            "local1 local2 'imported 1' 'imported 2' fred3";
        byte[] binary = encode(text);

        // Remove the imported table before decoding the binary.
        SimpleCatalog catalog = (SimpleCatalog) system().getCatalog();
        assertNotNull(catalog.removeTable("imported", 2));

        // FIXME this will fail until we inject missing max_id
        IonDatagram dg = loader().load(binary);
        checkSymbol("local1", local1id, dg.get(0));
        checkSymbol("local2", local2id, dg.get(1));
        checkSymbol("imported 1", import1id, dg.get(2));
        checkSymbol("imported 2", import2id, dg.get(3));
        checkSymbol("$" + fred3id, fred3id, dg.get(4));

        // We can't load the original text because it doesn't have max_id
        // and the table isn't in the catalog.
        badValue(text);
    }

    /**
     * Import v2 but catalog has v3.
     */
    public void testLocalTableWithGreaterImport()
    {
        final int import1id = ION_1_0_MAX_ID + 1;
        final int import2id = ION_1_0_MAX_ID + 2;
        final int fred3id   = ION_1_0_MAX_ID + 3;

        final int local1id = ION_1_0_MAX_ID + IMPORTED_2_MAX_ID + 1;
        final int local2id = local1id + 1;
        final int local3id = local2id + 1;

        registerImportedV1();
        registerImportedV2();
        SymbolTable i3 = registerImportedV3();

        // Make sure our syms don't overlap.
        assertTrue(i3.findSymbol("fred5") != local3id);

        // fred5 is not in table version 2, so it gets local symbol
        String text =
            LocalSymbolTablePrefix +
            "{" +
            "  imports:[{name:\"imported\", version:2,}]," +
            "}\n" +
            "local1 local2 'imported 1' 'imported 2' fred3 fred5";
        byte[] binary = encode(text);

        // Remove the imported table before decoding the binary.
        SimpleCatalog catalog = (SimpleCatalog) system().getCatalog();
        assertNotNull(catalog.removeTable("imported", 2));

        // FIXME this will fail until we inject missing max_id
        IonDatagram dg = loader().load(binary);
        checkSymbol("local1", local1id, dg.get(0));
        checkSymbol("local2", local2id, dg.get(1));
        checkSymbol("imported 1", import1id, dg.get(2));
        checkSymbol("$" + import2id, import2id, dg.get(3));
        checkSymbol("fred3", fred3id, dg.get(4));
        checkSymbol("fred5", local3id, dg.get(5));

        // We can't load the original text because it doesn't have max_id
        // and the table isn't in the catalog.
        badValue(text);
    }


    // TODO test empty imports:[]
    // TODO test non-array imports
    // TODO test getUsedTable(null)
    // TODO test getImportedTable(null)

    public void testImportWithZeroMaxId()
    {
        SymbolTable importedV1 = registerImportedV1();

        String text =
            LocalSymbolTablePrefix +
            "{" +
            "  imports:[{name:\"imported\", version:1, max_id:0}]," +
            "  symbols:['''local''']" +
            "}\n" +
            "null";
        IonValue v = oneValue(text);
        SymbolTable symbolTable = v.getSymbolTable();
        assertSame(importedV1, symbolTable.getImportedTables()[0]);
        assertEquals(ION_1_0_MAX_ID + 1, symbolTable.findSymbol("local"));
    }

    public void testImportWithBadMaxId()
    {
        SymbolTable importedV1 = registerImportedV1();

        testImportWithBadMaxId(importedV1, "null.int");
        testImportWithBadMaxId(importedV1, "null");
        testImportWithBadMaxId(importedV1, "not_an_int");
//        testImportWithBadMaxId(importedV1, "0");  Zero isn't bad, its zero!
        testImportWithBadMaxId(importedV1, "-1");
        testImportWithBadMaxId(importedV1, "-2223");

        String text =
            LocalSymbolTablePrefix +
            "{" +
            "  imports:[{name:\"imported\", version:1}]," +
            "}\n" +
            "null";
        IonValue v = oneValue(text);
        assertSame(importedV1, v.getSymbolTable().getImportedTables()[0]);

        SimpleCatalog catalog = (SimpleCatalog)system().getCatalog();
        catalog.removeTable(importedV1.getName(), importedV1.getVersion());
        badValue(text);
    }

    public void testImportWithBadMaxId(SymbolTable expected, String maxIdText)
    {
        String text =
            LocalSymbolTablePrefix +
            "{" +
            "  imports:[{name:\"imported\", version:1," +
            "            max_id:" + maxIdText +
            "  }]," +
            "}\n" +
            "null";
        IonValue v = oneValue(text);
        assertSame(expected, v.getSymbolTable().getImportedTables()[0]);
    }


    //-------------------------------------------------------------------------
    // Testing name field

    public void testSharedTableMissingName()
    {
        String text =
            SharedSymbolTablePrefix +
            "{" +
            "  version:1," +
            "  symbols:{ $100:\"x\" }\n" +
            "}";
        try
        {
            loadSharedSymtab(text);
            fail("Expected exception");
        }
        catch (IonException e) {
            assertTrue(e.getMessage().contains("no 'name'"));
        }
    }


    public void testMalformedSymtabName()
    {
        testMalformedSymtabName(" \"\" "); // empty string
        testMalformedSymtabName("null.string");
        testMalformedSymtabName("null");
        testMalformedSymtabName("a_symbol");
        testMalformedSymtabName("159");
    }

    public void testMalformedSymtabName(String nameText)
    {
        String text =
            SharedSymbolTablePrefix +
            "{" +
            "  name:" + nameText + "," +
            "  version:1," +
            "  symbols:[\"x\"]" +
            "}";
        try
        {
            loadSharedSymtab(text);
            fail("Expected exception");
        }
        catch (IonException e) {
            assertTrue(e.getMessage().contains("'name'"));
        }
    }


    //-------------------------------------------------------------------------
    // Testing version field

    public void testSharedTableMissingVersion()
    {
        String text =
            SharedSymbolTablePrefix +
            "{" +
            "  name:\"test\"," +
            "  symbols:{ $100:\"x\" }\n" +
            "}";
        SymbolTable symbolTable = loadSharedSymtab(text);

        // Version defaults to 1
        assertEquals(1, symbolTable.getVersion());
    }

    public void testMalformedVersionField()
    {
        testMalformedVersionField("-1");
        testMalformedVersionField("0");

        testMalformedVersionField("null.int");
        testMalformedVersionField("null");
        testMalformedVersionField("a_symbol");
        testMalformedVersionField("2.0");
    }

    public void testMalformedVersionField(String versionValue)
    {
        String text =
            SharedSymbolTablePrefix +
            "{" +
            "  name:\"test\"," +
            "  version:" + versionValue + "," +
            "  symbols:[\"x\", \"y\"]\n" +
            "}";

        SymbolTable table = registerSharedSymtab(text);
        assertEquals("test", table.getName());
        assertEquals(1, table.getVersion());
        assertEquals(2, table.size());


        text =
            LocalSymbolTablePrefix +
            "{ imports:[{ name:\"test\"," +
            "             version:" + versionValue + "}]" +
            "}\n" +
            "y";

        IonValue v = oneValue(text);
        checkSymbol("y", ION_1_0_MAX_ID + 2, v);
        assertSame(table, v.getSymbolTable().getImportedTables()[0]);
    }


    //-------------------------------------------------------------------------
    // Testing symbols field

    public void testMalformedSymbolsField()
    {
        testMalformedSymbolsField("{}");
        testMalformedSymbolsField("null.struct");
        testMalformedSymbolsField("null");
        testMalformedSymbolsField("a_symbol");
        testMalformedSymbolsField("100");
    }

    public void testMalformedSymbolsField(String symbolValue)
    {
        String text =
            SharedSymbolTablePrefix +
            "{" +
            "  symbols:" + symbolValue + "," +   // Keep symbols first
            "  name:\"test\", version:5," +
            "}";
        SymbolTable table = registerSharedSymtab(text);
        assertEquals("test", table.getName());
        assertEquals(5, table.getVersion());
        assertEquals(0, table.getMaxId());
        assertEquals(0, table.size());

        text =
            LocalSymbolTablePrefix +
            "{symbols:" + symbolValue + "} " +
            "null";
        IonValue v = oneValue(text);
        table = v.getSymbolTable();
        assertTrue(table.isLocalTable());
        assertEquals(ION_1_0_MAX_ID, table.getMaxId());
        assertEquals(0, table.size());
    }

    public void testMalformedSymbolDeclarations()
    {
        testMalformedSymbolDeclaration(" \"\" ");      // empty string
        testMalformedSymbolDeclaration("null.string");
        testMalformedSymbolDeclaration("null");
        testMalformedSymbolDeclaration("a_symbol");
        testMalformedSymbolDeclaration("100");
        testMalformedSymbolDeclaration("['''whee''']");
    }

    public void testMalformedSymbolDeclaration(String symbolValue)
    {
        String text =
            SharedSymbolTablePrefix +
            "{" +
            "  name:\"test\", version:1," +
            "  symbols:[" + symbolValue + "]" +
            "}";
        SymbolTable table = registerSharedSymtab(text);
        assertEquals(1, table.getMaxId());
        assertEquals(1, table.size());
        assertEquals(null, table.findKnownSymbol(1));
        assertEquals("$1", table.findSymbol(1));

        text =
            SharedSymbolTablePrefix +
            "{" +
            "  name:\"test\", version:1," +
            "  symbols:{$100:" + symbolValue + "}" +
            "}";
        table = registerSharedSymtab(text);
        assertEquals(100, table.getMaxId());
        assertEquals(100, table.size());
        assertEquals(null, table.findKnownSymbol(100));

        text =
            LocalSymbolTablePrefix +
            "{symbols:[" + symbolValue + "]} " +
            "null";
        IonValue v = oneValue(text);
        table = v.getSymbolTable();
        assertTrue(table.isLocalTable());
        assertEquals(ION_1_0_MAX_ID + 1, table.getMaxId());
        assertEquals(1, table.size());

        text =
            LocalSymbolTablePrefix +
            "{symbols:{$100:" + symbolValue + "}} " +
            "null";
        v = oneValue(text);
        table = v.getSymbolTable();
        assertTrue(table.isLocalTable());
        assertEquals(100, table.getMaxId());
        assertEquals(100-ION_1_0_MAX_ID, table.size());
    }


    public void testSystemIdOnNonStruct()
    {
        String text = "$ion_1_0::12";
        IonInt v = (IonInt) oneValue(text);
        checkInt(12, v);
    }

    public void testSymbolTableOnNonStruct()
    {
        String text = "$ion_symbol_table::12";
        IonInt v = (IonInt) oneValue(text);
        checkInt(12, v);
    }

    public void testNestedSystemId()
    {
        String text = "($ion_1_0)";
        IonSexp v = oneSexp(text);
        checkSymbol(ION_1_0, v.get(0));
    }

    public void XXXtestKimSymbols() throws Exception
    {
//    	File input = new File("c:\\data\\samples\\kim.10n");
//    	File symbols = new File("c:\\data\\samples\\kim_symbols.ion");
//    	IonDatagram dg = this.mySystem.getLoader().load(symbols);
    	SymbolTable symtab = mySystem.getCatalog().getTable("ims.item");
    	IonStruct   str = symtab.getIonRepresentation();

    	UnifiedSymbolTable ust = new UnifiedSymbolTable(UnifiedSymbolTable.getSystemSymbolTableInstance());

    	IonStruct syms = (IonStruct)str.get("symbols");

    	for (IonValue v : syms) {
    		String name  = ((IonText)v).stringValue();
    		int    id    = v.getFieldId();
    		int    newid = ust.addSymbol(name);
    		assertTrue(id == newid);
    	}
    	ust.share("ims.item", 1);

    	IonCatalog catalog = mySystem.getCatalog();
    	catalog.putTable(ust);

    	IonTextWriter w = new IonTextWriter();

    	byte[] buf = openFileForBuffer("c:\\data\\samples\\kim.10n");
    	IonReader r = mySystem.newReader(buf);

    	w.writeValues(r);

    	byte[] output = w.getBytes();

    	String s_output = new String(output, "UTF-8");
    	System.out.println(s_output);
    }


    static byte[] openFileForBuffer(Object arg) {
        FileInputStream is = null;
        byte[] buf = null;

        if (arg instanceof String) {
            String filename = (String)arg;
            File f = new File(filename);
            if (!f.canRead()) {
                throw new IllegalArgumentException("can't read the file " + filename);
            }
            try {
                is =  new FileInputStream(f);
                if (f.length() < 1 || f.length() > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("file is too long to load into a buffer: " + filename + " len = "+f.length());
                }
                int len = (int)f.length();
                buf = new byte[len];
                try {
                    if (is.read(buf) != len) {
                        throw new IOException ("failed to read file into buffer: " + filename);
                    }
                }
                catch (IOException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            catch (FileNotFoundException e){
                throw new IllegalArgumentException("can't read the file " + filename);
            }
        }
        else {
            throw new IllegalArgumentException("string routines need a filename");
        }
        return buf;
    }

}
