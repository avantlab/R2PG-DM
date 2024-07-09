package com.java.r2pgdm.schema;

import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import org.apache.commons.io.FileUtils;
import org.json.JSONObject;

import com.java.r2pgdm.CompositeForeignKey;
import com.java.r2pgdm.InputConnection;

/**
 * The `PGSchema` class is responsible for generating the schema of the target
 */
public class PGSchema {

    // The metadata of a relational database should be the input of the class

    @Getter
    @Setter
    String schemaName;

    @Getter
    @Setter
    DatabaseMetaData metadata;

    @Getter
    @Setter
    InputConnection targetDatabase;

    @Getter
    @Setter
    List<String> tables;

    @Getter
    @Setter
    Map<String, List<CompositeForeignKey>> joinTables;

    @Getter
    @Setter
    List<CompositeForeignKey> compositeForeignKeys;

    @Getter
    @Setter
    String schema;

    private static Map<String, Set<String>> nodes = new HashMap<>();
    private static Map<String, Set<String>> edges = new HashMap<>();
    private static Map<String, String> nodeLabels = new HashMap<>();
    private static Map<String, Set<String>> edgeStartLabels = new HashMap<>();
    private static Map<String, Set<String>> edgeEndLabels = new HashMap<>();
    private static Map<String, Set<String>> startNodeLabels = new HashMap<>();
    private static Map<String, Set<String>> endNodeLabels = new HashMap<>();

    /**
     * Constructs a new `PGSchema` object with the specified schema name, database
     * metadata, and target database connection.
     * 
     * @param schemaName     The name of the schema.
     * @param metadata       The metadata of the database.
     * @param targetDatabase The connection to the target database.
     * @param joinTables
     * @param tables
     */
    public PGSchema(String schemaName, DatabaseMetaData metadata, InputConnection targetDatabase, List<String> tables,
            Map<String, List<CompositeForeignKey>> joinTables, List<CompositeForeignKey> fks) {
        this.schemaName = schemaName;
        this.metadata = metadata;
        this.targetDatabase = targetDatabase;
        this.tables = tables;
        this.joinTables = joinTables;
        this.compositeForeignKeys = fks;

        createSchema();
    }

    private void createSchema() {
        schema = createType("GraphType");
        schema += EOF();
        cleanupSchema();

        compareSchemaAndTarget();

        // System.out.println("\nOutput - Schema:\n");
        // System.out.println(schema);
    }

    private String SP() {
        return " ";
    }

    private String createType(String typeName) {
        switch (typeName) {
            case "NodeType":
                return createNodeType(typeName);
            case "EdgeType":
                return createEdgeType(typeName);
            case "GraphType":
                return createGraphType(this.schemaName);
            default:
                return "";
        }
    }

    private String createNodeType(String nodeTypeName) {
        return "CREATE NODE TYPE " + isAbstract(false) + nodeTypeName;
    }

    private String createEdgeType(String edgeTypeName) {
        return "CREATE EDGE TYPE " + isAbstract(false) + edgeTypeName;
    }

    private String createGraphType(String graphTypeName) {
        return "CREATE GRAPH TYPE " + getGraphType(graphTypeName);
    }

    private String getGraphType(String graphTypeName) {
        return graphTypeName + "GraphType" + SP() + getTypeForm(true) + SP() + getGraphTypeDefinition();
    }

    private String getNodeType(String nodeTypeName) {
        return SP() + "(" + nodeTypeName + "Type" + getNodeLabelPropertySpec(nodeTypeName) + ")";
    }

    private String getEdgeType(String edgeTypeName, String sourceNodeType, String targetNodeType) {
        return SP() + "(:" + sourceNodeType + "Type)-[" + edgeTypeName + "Type" + getEdgeLabelPropertySpec(edgeTypeName)
                + "]->(:" + targetNodeType + "Type)";
    }

    private String getNodeLabelPropertySpec(String TypeName) {
        return ":" + SP() + TypeName + SP() + isOpen(false) + getProperties(TypeName);

    }

    private String getEdgeLabelPropertySpec(String TypeName) {
        if (this.joinTables.containsKey(TypeName)) {
            return ":" + SP() + TypeName + SP() + isOpen(false) + getProperties(TypeName);
        } else {
            return ":" + SP() + TypeName;
        }

    }

    private String getProperties(String nodeTypeName) {
        try {
            ResultSet rs = metadata.getColumns(null, null, nodeTypeName, null);
            StringBuilder sb = new StringBuilder();

            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String columnType = rs.getString("TYPE_NAME");

                // Capitalize columntype and make it the GQL standard types
                columnType = getGQLType(columnType.split(" ")[0].toLowerCase());

                sb.append(columnName).append(SP()).append(columnType).append(",").append(SP());
            }

            // Remove the last comma and space
            sb.delete(sb.length() - 2, sb.length());

            return "{" + sb.toString() + "}";
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    private String isOpen(Boolean bool) {
        if (bool) {
            return "(OPEN)?" + SP();
        } else {
            return "";
        }
    }

    private String getTypeForm(Boolean Strict) {
        if (Strict) {
            return "STRICT";
        } else {
            return "LOOSE";
        }
    }

    private String isAbstract(Boolean bool) {
        if (bool) {
            return "(ABSTRACT)?" + SP();
        } else {
            return "";
        }
    }

    private String getGraphTypeDefinition() {

        // String builder
        StringBuilder sb = new StringBuilder();

        for (String table : tables) {
            sb.append(SP()).append(getNodeType(table)).append(",\n");
        }

        for (String table : joinTables.keySet()) {
            List<CompositeForeignKey> compositeForeignKeys = joinTables.get(table);
            // Get the targetTables from both compositeForeignKeys and link them with an
            // edge, where the edge is called the sourceTable and has properties of the
            // source table
            sb.append(SP())
                    .append(getEdgeType(compositeForeignKeys.get(0).getSourceTable(),
                            compositeForeignKeys.get(0).getTargetTable(), compositeForeignKeys.get(1).getTargetTable()))
                    .append(",\n");
            sb.append(SP())
                    .append(getEdgeType(compositeForeignKeys.get(1).getSourceTable(),
                            compositeForeignKeys.get(1).getTargetTable(), compositeForeignKeys.get(0).getTargetTable()))
                    .append(",\n");
        }

        // Loop through all foreign keys
        for (CompositeForeignKey compositeForeignKey : this.compositeForeignKeys) {
            sb.append(SP())
                    .append(getEdgeType(
                            compositeForeignKey.getSourceTable() + "-" + compositeForeignKey.getTargetTable(),
                            compositeForeignKey.getSourceTable(),
                            compositeForeignKey.getTargetTable()))
                    .append(",\n");
        }

        // Remove the last comma
        sb.deleteCharAt(sb.length() - 2);

        return "{\n" + sb.toString() + "}";
    }

    private String EOF() {
        return "\n";
    }

    private void cleanupSchema() {
        // No two lines should be the same, if there are, remove one
        String[] lines = this.schema.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == 0) {
                sb.append(lines[i]).append("\n");
            } else {
                if (!lines[i].equals(lines[i - 1])) {
                    sb.append(lines[i]).append("\n");
                }
            }
        }
        this.schema = sb.toString();
    }

    private void compareSchemaAndTarget() {
        // Read the json file
        String content = null;
        try {
            content = new String(Files.readAllBytes(Paths.get("exports/combined.json")));
        } catch (IOException e) {
            e.printStackTrace();
        }

        String[] lines = content.split("\n");

        for (String line : lines) {
            JSONObject jsonObject = new JSONObject(line);
            String type = jsonObject.getString("type");

            if (type.equals("node")) {
                parseNode(jsonObject);
            } else if (type.equals("relationship")) {
                parseEdge(jsonObject);
            }
        }

        // Parse the schema
        Map<String, Set<String>> schemaNodes = new HashMap<>();
        Map<String, Set<String>> schemaEdges = new HashMap<>();

        parseSchema(schema, schemaNodes, schemaEdges);

        // Compare nodes and edges with schema
        compareWithSchema(schemaNodes, schemaEdges);
    }

    private static void parseNode(JSONObject jsonObject) {
        String label = jsonObject.getJSONArray("labels").getString(0);
        JSONObject properties = jsonObject.getJSONObject("properties");

        // Add every id and label to the nodeLabels map
        nodeLabels.put(jsonObject.getString("id"), label);

        // Retrieve or create a set to represent the node properties for the label
        Set<String> propertiesSet = nodes.computeIfAbsent(label, k -> new HashSet<String>());

        // Add properties to the set
        for (String key : properties.keySet()) {
            propertiesSet.add(key);
        }
    }

    private static void parseEdge(JSONObject jsonObject) {
        String label = jsonObject.getString("label");
        JSONObject start = jsonObject.getJSONObject("start");
        JSONObject end = jsonObject.getJSONObject("end");
        JSONObject properties = jsonObject.getJSONObject("properties");

        // TODO: Get the labels from the start and end node from the schema and compare
        // them to the labels in the json object
        String id_start = start.getString("id");
        String id_end = end.getString("id");

        // Retrieve the node labels for the start and end node
        String label_start = nodeLabels.get(id_start);
        String label_end = nodeLabels.get(id_end);

        // Create a map to represent the relationship
        Set<String> propertiesSet = edges.computeIfAbsent(label, k -> new HashSet<String>());
        Set<String> edgeStartLabelsSet = edgeStartLabels.computeIfAbsent(label, k -> new HashSet<String>());
        Set<String> edgeEndLabelsSet = edgeEndLabels.computeIfAbsent(label, k -> new HashSet<String>());

        // Add properties to the set
        for (String key : properties.keySet()) {
            propertiesSet.add(key);
        }

        // Add start and end labels to the set
        edgeStartLabelsSet.add(label_start);
        edgeEndLabelsSet.add(label_end);
    }

    private static void parseSchema(String schema, Map<String, Set<String>> schemaNodes,
            Map<String, Set<String>> schemaEdges) {
        // Regular expressions to extract node and edge definitions
        Pattern nodePattern = Pattern.compile("\\((\\w+Type): (\\w+) \\{([^}]*)}");
        Pattern edgePattern = Pattern
                .compile("\\(\\:[a-zA-Z]+Type\\)\\-\\[([a-zA-Z\\-]+):\\s+([a-zA-Z\\-]+)\\]\\->\\(\\:[a-zA-Z]+Type\\)");

        Matcher nodeMatcher = nodePattern.matcher(schema);
        while (nodeMatcher.find()) {
            String nodeType = nodeMatcher.group(2);
            String properties = nodeMatcher.group(3);

            Set<String> propertiesSet = new HashSet<>(Arrays.asList(properties.split(",\\s*")));

            // From every property, only get the label and not the type. e.g. Capital INT ->
            // Capital
            Set<String> newPropertiesSet = new HashSet<>();
            for (String property : propertiesSet) {
                newPropertiesSet.add(property.split(" ")[0]);
            }

            schemaNodes.put(nodeType, newPropertiesSet);
        }

        Matcher edgeMatcher = edgePattern.matcher(schema);
        while (edgeMatcher.find()) {
            String edgeType = edgeMatcher.group(2);

            Set<String> newPropertiesSet = new HashSet<>();
            Set<String> startNodeLabelsSet = new HashSet<>();
            Set<String> endNodeLabelsSet = new HashSet<>();

            // Get the node types of the start and end nodes and put them in
            // schemaEdgeNodeLabels where the start node is the first (\\:[a-zA-Z]+Type\\)
            // and the end node the last

            String startNode = edgeMatcher.group(0).split("-")[0];
            String endNode = edgeMatcher.group(0).split("->")[1];

            // Replace the : and (:) with nothing
            startNode = startNode.replace(":", "").replace("(", "").replace(")", "").replace("Type", "");
            endNode = endNode.replace(":", "").replace("(", "").replace(")", "").replace("Type", "");

            // Add the start and end node labels to the set
            startNodeLabelsSet.add(startNode);
            endNodeLabelsSet.add(endNode);

            startNodeLabels.put(edgeType, startNodeLabelsSet);
            endNodeLabels.put(edgeType, endNodeLabelsSet);

            schemaEdges.put(edgeType, newPropertiesSet);
        }
    }

    private static void compareWithSchema(Map<String, Set<String>> schemaNodes, Map<String, Set<String>> schemaEdges) {
        // Compare node types

        // Nodes and schemaNodes must be the same, thus also the count of labels
        if (nodes.size() != schemaNodes.size()) {
            System.out.println("!! Node types do not match schema.");
            System.out.println("Node types: " + nodes.keySet());
            System.out.println("Schema node types: " + schemaNodes.keySet());
        }

        // Same for edges
        if (edges.size() != schemaEdges.size()) {
            System.out.println("!! Edge types do not match schema.");
            System.out.println("Edge types: " + edges.keySet());
            System.out.println("Schema edge types: " + schemaEdges.keySet());
        }

        for (String nodeType : schemaNodes.keySet()) {
            if (nodes.containsKey(nodeType)) {
                Set<String> nodeProperties = nodes.get(nodeType);
                Set<String> schemaProperties = schemaNodes.get(nodeType);

                List<String> sortedNodeProperties = new ArrayList<>(nodeProperties);
                Collections.sort(sortedNodeProperties);

                List<String> sortedSchemaProperties = new ArrayList<>(schemaProperties);
                Collections.sort(sortedSchemaProperties);

                if (sortedNodeProperties.equals(sortedSchemaProperties)) {
                    // System.out.println("Node type " + nodeType + " matches schema.");
                } else {
                    System.out.println("!! Node type " + nodeType + " does not match schema.");
                    System.out.println("Node properties: " + sortedNodeProperties);
                    System.out.println("Schema properties: " + sortedSchemaProperties);
                }
            } else {
                System.out.println("!! Node type " + nodeType + " is missing in parsed data.");
            }
        }

        // Compare edge types
        for (String edgeType : schemaEdges.keySet()) {
            if (edges.containsKey(edgeType)) {
                Set<String> edgeProperties = edges.get(edgeType);
                Set<String> schemaProperties = schemaEdges.get(edgeType);

                List<String> sortedEdgeProperties = new ArrayList<>(edgeProperties);
                Collections.sort(sortedEdgeProperties);

                List<String> sortedSchemaProperties = new ArrayList<>(schemaProperties);
                Collections.sort(sortedSchemaProperties);

                // Compare the start and end node labels
                Set<String> startNodeLabelsSet = edgeStartLabels.get(edgeType);
                Set<String> endNodeLabelsSet = edgeEndLabels.get(edgeType);

                Set<String> schemaStartNodeLabelsSet = startNodeLabels.get(edgeType);
                Set<String> schemaEndNodeLabelsSet = endNodeLabels.get(edgeType);

                List<String> sortedStartNodeLabels = new ArrayList<>(startNodeLabelsSet);
                Collections.sort(sortedStartNodeLabels);

                List<String> sortedEndNodeLabels = new ArrayList<>(endNodeLabelsSet);
                Collections.sort(sortedEndNodeLabels);

                List<String> sortedSchemaStartNodeLabels = new ArrayList<>(schemaStartNodeLabelsSet);
                Collections.sort(sortedSchemaStartNodeLabels);

                List<String> sortedSchemaEndNodeLabels = new ArrayList<>(schemaEndNodeLabelsSet);
                Collections.sort(sortedSchemaEndNodeLabels);

                if (sortedStartNodeLabels.equals(sortedSchemaStartNodeLabels)
                        && sortedEndNodeLabels.equals(sortedSchemaEndNodeLabels)) {
                    // System.out.println("Edge type " + edgeType + " matches schema.");
                } else {
                    System.out.println("!! Edge type " + edgeType + " does not match schema.");
                    System.out.println("Edge start node labels: " + sortedStartNodeLabels);
                    System.out.println("Edge end node labels: " + sortedEndNodeLabels);
                    System.out.println("Schema start node labels: " + sortedSchemaStartNodeLabels);
                    System.out.println("Schema end node labels: " + sortedSchemaEndNodeLabels);
                }

                if (sortedEdgeProperties.equals(sortedSchemaProperties)) {
                    // System.out.println("Edge type " + edgeType + " matches schema.");
                } else {
                    System.out.println("!! Edge type " + edgeType + " does not match schema.");
                    System.out.println("Edge properties: " + sortedEdgeProperties);
                    System.out.println("Schema properties: " + sortedSchemaProperties);
                }
            } else {
                System.out.println("!! Edge type " + edgeType + " is missing in parsed data.");
            }
        }
    }

    public void exportGraph(String filePath) {
        // Export schema to file path with .pgs extension
        try {
            FileUtils.writeStringToFile(new File(filePath + "\\schema.pgs"), schema, "UTF-8");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void validateSchema() {
        // Validate schema
        String schema = this.schema;

        pgsLexer lexer = new pgsLexer(CharStreams.fromString(schema));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        pgsParser parser = new pgsParser(tokens);
        ParseTree tree = parser.pgs();

        // Check if there is an error, if not it is valid
        if (parser.getNumberOfSyntaxErrors() > 0) {
            System.err.println("\nOutput - Syntax errors found in schema");
            System.out.println(tree.toStringTree(parser));
        } else {
            System.out.println("Output - Generated schema is valid");
        }
    }

    // Create a mapping from mysql to GQL types
    // https://dev.mysql.com/doc/refman/8.0/en/data-types.html
    // https://graphql.org/learn/schema/

    public static final Map<String, String> MapToGQL = new HashMap<String, String>();

    static {
        // Common to all
        MapToGQL.put("id", "ID");
        MapToGQL.put("boolean", "Boolean");
        MapToGQL.put("bool", "Boolean");
        MapToGQL.put("int", "Int");
        MapToGQL.put("tinyint", "Int");
        MapToGQL.put("smallint", "Int");
        MapToGQL.put("mediumint", "Int");
        MapToGQL.put("bigint", "Int");
        MapToGQL.put("float", "Float");
        MapToGQL.put("double", "Float");

        // MySQL to GraphQL
        MapToGQL.put("varchar", "String");
        MapToGQL.put("char", "String");
        MapToGQL.put("tinytext", "String");
        MapToGQL.put("mediumtext", "String");
        MapToGQL.put("longtext", "String");
        MapToGQL.put("real", "Float");
        MapToGQL.put("decimal", "Float");
        MapToGQL.put("date", "String");
        MapToGQL.put("time", "String");
        MapToGQL.put("datetime", "String");
        MapToGQL.put("timestamp", "String");
        MapToGQL.put("year", "String");

        // SQL Server to GraphQL
        MapToGQL.put("nvarchar", "String");
        MapToGQL.put("nchar", "String");
        MapToGQL.put("text", "String");
        MapToGQL.put("bit", "Boolean");

        // PostgreSQL to GraphQL
        MapToGQL.put("integer", "Int");
        MapToGQL.put("double precision", "Float");
        MapToGQL.put("timestamptz", "String");
        MapToGQL.put("timetz", "String");

        // SQLite to GraphQL
        MapToGQL.put("blob", "String");
    }

    public static String getGQLType(String mysqlType) {
        for (String key : MapToGQL.keySet()) {
            if (mysqlType.toLowerCase().contains(key)) {
                return MapToGQL.get(key).toUpperCase();
            }
        }
        return "UNKNOWN";
    }

}
