package com.java.r2pgdm;

import com.opencsv.CSVReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end test for the R2PG-DM mapping pipeline.
 *
 * Builds a small SQLite database with a relational schema containing:
 *   - a one-to-many FK (books -> authors)
 *   - a many-to-many join table (book_tags between books and tags)
 *
 * Then drives App.run() and asserts on the produced nodes / edges / properties.
 * This exercises the actual claims made in SciLake D2.2 §4.2.1: nodes for each
 * tuple, edges for FKs, and edge-with-properties for join tables.
 */
public class EndToEndTest {

    private Path tmpDir;
    private File dbFile;
    private File configFile;
    private File exportDir;

    @Before
    public void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("r2pgdm-e2e-");
        dbFile = new File(tmpDir.toFile(), "fixture.db");
        configFile = new File(tmpDir.toFile(), "config.ini");
        exportDir = new File(tmpDir.toFile(), "exports");
        exportDir.mkdirs();

        // Build the fixture DB.
        // R2PG-DM opens a 64-thread connection pool; under sqlite that fights the
        // per-file write lock. WAL mode + a long busy_timeout makes writers serialize
        // and wait instead of erroring with SQLITE_BUSY.
        String jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath()
                + "?journal_mode=WAL&busy_timeout=30000";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement s = conn.createStatement()) {
            s.executeUpdate("CREATE TABLE authors (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT NOT NULL, " +
                    "author_id INTEGER NOT NULL REFERENCES authors(id))");
            s.executeUpdate("CREATE TABLE tags (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE book_tags (" +
                    "book_id INTEGER NOT NULL REFERENCES books(id), " +
                    "tag_id INTEGER NOT NULL REFERENCES tags(id), " +
                    "weight INTEGER NOT NULL, " +
                    "PRIMARY KEY (book_id, tag_id))");

            s.executeUpdate("INSERT INTO authors VALUES (1, 'Ursula Le Guin'), (2, 'Iain Banks')");
            s.executeUpdate("INSERT INTO books VALUES " +
                    "(10, 'A Wizard of Earthsea', 1), " +
                    "(11, 'The Dispossessed', 1), " +
                    "(12, 'Consider Phlebas', 2)");
            s.executeUpdate("INSERT INTO tags VALUES (100, 'fantasy'), (101, 'scifi'), (102, 'classic')");
            s.executeUpdate("INSERT INTO book_tags VALUES " +
                    "(10, 100, 5), (10, 102, 3), " +
                    "(11, 101, 5), (11, 102, 4), " +
                    "(12, 101, 5)");
        }

        // Write a config. Same DB for input and output so App skips table copying.
        try (PrintWriter w = new PrintWriter(new FileWriter(configFile))) {
            w.println("[input]");
            w.println("connectionString=" + jdbcUrl);
            w.println("database=fixture");
            w.println("driver=sqlite");
            w.println();
            w.println("[output]");
            w.println("connectionString=" + jdbcUrl);
            w.println("database=fixture");
            w.println("driver=sqlite");
            w.println();
            w.println("[mapping]");
            w.println("tables=true");
            w.println("views=false");
            w.println("schema=");
            w.println("tableNames=*");
            w.println("deleteCopy=false");
        }
    }

    @After
    public void tearDown() throws Exception {
        // Best-effort recursive delete.
        if (tmpDir != null && Files.exists(tmpDir)) {
            Files.walk(tmpDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    public void mapsRelationalDbToPropertyGraph() throws Exception {
        App.run(configFile, exportDir.getAbsolutePath());

        File nodesCsv = new File(exportDir, "nodes.csv");
        File edgesCsv = new File(exportDir, "edges.csv");
        File propsCsv = new File(exportDir, "properties.csv");
        File combinedJson = new File(exportDir, "combined.json");
        File schemaPgs = new File(exportDir, "schema.pgs");

        assertTrue("nodes.csv missing", nodesCsv.exists());
        assertTrue("edges.csv missing", edgesCsv.exists());
        assertTrue("properties.csv missing", propsCsv.exists());
        assertTrue("combined.json missing", combinedJson.exists());
        assertTrue("schema.pgs missing", schemaPgs.exists());

        List<String[]> nodes = readCsv(nodesCsv);
        List<String[]> edges = readCsv(edgesCsv);
        List<String[]> props = readCsv(propsCsv);

        // ---- nodes: one per non-join tuple = 2 authors + 3 books + 3 tags = 8 ----
        Map<String, Integer> nodeLabelCounts = new HashMap<>();
        for (String[] row : nodes) {
            nodeLabelCounts.merge(row[1], 1, Integer::sum);
        }
        assertEquals("expected 2 author nodes", Integer.valueOf(2), nodeLabelCounts.get("authors"));
        assertEquals("expected 3 book nodes",   Integer.valueOf(3), nodeLabelCounts.get("books"));
        assertEquals("expected 3 tag nodes",    Integer.valueOf(3), nodeLabelCounts.get("tags"));
        assertEquals("expected 8 nodes total", 8, nodes.size());

        // ---- edges ----
        // 3 FK edges from books.author_id -> authors.id (one per book)
        // book_tags is a 2-FK join table; the current implementation emits *two*
        // directed edges per join row (forward + reverse), so 5 rows -> 10 edges.
        Map<String, Integer> edgeLabelCounts = new HashMap<>();
        for (String[] row : edges) {
            edgeLabelCounts.merge(row[3], 1, Integer::sum);
        }
        assertEquals("expected 13 edges total (3 FK + 10 join bidi)", 13, edges.size());
        assertEquals("expected 10 book_tags edges (5 rows, bidirectional)",
                Integer.valueOf(10), edgeLabelCounts.get("book_tags"));

        // ---- properties ----
        // Node properties: every column on every tuple becomes a property:
        //   authors (id, name) * 2 = 4
        //   books   (id, title, author_id) * 3 = 9
        //   tags    (id, name) * 3 = 6   -> 19 node properties
        // Edge properties: each of the 10 join-table edges gets a property row for
        // every column in the join-tuple result set the SQL produced
        // (book_id, tag_id, weight, plus the two targetId columns the WITH-CTE adds).
        // We don't pin the exact count of those edge-property rows here; instead we
        // require *at least* the 19 node properties, and verify the weight value
        // we inserted shows up.
        assertTrue("fewer properties than the 19 expected on nodes",
                props.size() >= 19);
        boolean foundWeight5 = false;
        for (String[] row : props) {
            if ("weight".equals(row[1]) && "5".equals(row[2])) {
                foundWeight5 = true;
                break;
            }
        }
        assertTrue("book_tags.weight=5 missing from properties.csv (join-table " +
                "edge property never made it through)", foundWeight5);

        // ---- referential integrity: every edge endpoint must be a real node ----
        Set<String> nodeIds = new HashSet<>();
        for (String[] row : nodes) nodeIds.add(row[0]);
        for (String[] row : edges) {
            assertTrue("edge src not a node: " + row[1], nodeIds.contains(row[1]));
            assertTrue("edge tgt not a node: " + row[2], nodeIds.contains(row[2]));
        }

        // ---- check a known property survives the round-trip ----
        // 'A Wizard of Earthsea' should appear as a property value somewhere.
        boolean foundTitle = false;
        for (String[] row : props) {
            if ("title".equals(row[1]) && "A Wizard of Earthsea".equals(row[2])) {
                foundTitle = true;
                break;
            }
        }
        assertTrue("title 'A Wizard of Earthsea' missing from properties.csv", foundTitle);
    }

    private static List<String[]> readCsv(File f) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (CSVReader r = new CSVReader(new FileReader(f))) {
            String[] header = r.readNext(); // skip
            if (header == null) return rows;
            String[] row;
            while ((row = r.readNext()) != null) rows.add(row);
        }
        return rows;
    }
}
