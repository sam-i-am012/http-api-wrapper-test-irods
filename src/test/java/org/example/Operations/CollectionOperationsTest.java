package org.example.Operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.Properties.Collection.CollectionsListParams;
import org.example.Properties.Collection.CollectionsRemoveParams;
import org.example.Serialize.ModifyMetadataOperations;
import org.example.Serialize.ModifyPermissionsOperations;
import org.example.Util.Permission;
import org.example.Wrapper;
import org.example.Util.Response;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class CollectionOperationsTest {

    private Wrapper rods;
    private String rodsToken;

    private Wrapper alice;
    private String aliceToken;

    private ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setup() {
        String address = "52.91.145.195";
        String port = "8888";
        String version = "0.3.0";

        String baseUrl = "http://" + address + ":" + port + "/irods-http-api/" + version;

        // Create client
        rods = new Wrapper(baseUrl, "rods", "rods");
        rods.authenticate();
        rodsToken = rods.getAuthToken();

        // Create alice user
        rods.userGroupOperations().create_user(rodsToken, "alice", "tempZone", "rodsuser");
        rods.userGroupOperations().set_password(rodsToken, "alice", "tempZone", "alicepass");
        alice = new Wrapper(baseUrl, "alice", "alicepass");
        alice.authenticate();
        aliceToken = alice.getAuthToken();

        resetPath(rods, rodsToken, "/tempZone/home/rods");
    }

    @Test
    public void testCommonOperations() {
        Response res;
        String collectionPath = "/tempZone/home/rods/common_ops";

        // Create a new Collection.
        res = rods.collections().create(rodsToken, collectionPath, OptionalInt.empty());
        assertEquals("Creating collection request failed", 200, res.getHttpStatusCode());
        assertEquals("Creating collection failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Stat the collection to show that it exists.
        res = rods.collections().stat(rodsToken, collectionPath, Optional.empty());
        assertEquals("Stat request failed", 200, res.getHttpStatusCode());
        assertEquals("Stat failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Rename the collection.
        String newCollectionPath = collectionPath + ".renamed";
        res = rods.collections().rename(rodsToken, collectionPath, newCollectionPath);
        assertEquals("Renaming collections request failed", 200, res.getHttpStatusCode());
        assertEquals("Renaming collections failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Stat the original collection to show that it does not exist.
        res = rods.collections().stat(rodsToken, collectionPath, Optional.empty());
        assertEquals("Stat request failed", 200, res.getHttpStatusCode());
        assertEquals("Stat did not fail as expected", -170000,
                getIrodsResponseStatusCode(res.getBody()));
        // -170000: NOT_A_COLLECTION

        // Stat the new collection to show that it does exist.
        res = rods.collections().stat(rodsToken, newCollectionPath, Optional.empty());
        assertEquals("Stat request failed", 200, res.getHttpStatusCode());
        assertEquals("Stat failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Give another user (alice) permission to read the object.
        res = rods.collections().set_permission(rodsToken, newCollectionPath, "alice", Permission.READ,
                OptionalInt.empty());
        assertEquals("Setting permission request failed", 200, res.getHttpStatusCode());
        assertEquals("Setting permission failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Show that the alice user now has read permission on the collection.
        Response statRes = rods.collections().stat(rodsToken, newCollectionPath, Optional.empty());
        assertEquals("Stat request failed", 200, statRes.getHttpStatusCode());
        assertEquals("Stat failed", 0,
                getIrodsResponseStatusCode(statRes.getBody()));

        JsonNode rootNode = assertDoesNotThrow(() -> mapper.readTree(statRes.getBody()),
                "JsonProcessingException was thrown");

        JsonNode permissionsNode = rootNode.path("permissions");
        boolean permissionExists = false;
        for (JsonNode permission : permissionsNode) {
            if ("alice".equals(permission.path("name").asText()) &&
                    "read_object".equals(permission.path("perm").asText()) &&
                    "rodsuser".equals(permission.path("type").asText()) &&
                    "tempZone".equals(permission.path("zone").asText())) {
                permissionExists = true;
                break;
            }
        }
        assertTrue("Permission with specified attributes does not exist.", permissionExists);

        // Remove the collection.
        res = rods.collections().remove(rodsToken, newCollectionPath, null);
        assertEquals("Removing collection request failed", 200, res.getHttpStatusCode());
        assertEquals("Removing collection failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Stat the original collection to show that it does not exist.
        res = rods.collections().stat(rodsToken, newCollectionPath, Optional.empty());
        assertEquals("Stat request failed", 200, res.getHttpStatusCode());
        assertEquals("Stat did not fail as expected", -170000,
                getIrodsResponseStatusCode(res.getBody()));
        // -170000: NOT_A_COLLECTION
    }

    @Test
    public void testListOperation() {
        Response res;
        String homeCollection = "/tempZone/home/alice";

        // Created nested collections
        String collection = homeCollection + "/c0/c1/c2";
        res = alice.collections().create(aliceToken, collection, OptionalInt.of(1));
        assertEquals("Creating collection request failed", 200, res.getHttpStatusCode());
        assertEquals("Creating collection failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Create one data object in each collection.
        List<String> dataObjects = new ArrayList<>();
        dataObjects.add(homeCollection + "/c0/d0");
        dataObjects.add(homeCollection + "/c0/c1/d1");
        dataObjects.add(homeCollection + "/c0/c1/c2/d2");

        for (String name : dataObjects) {
            res = alice.dataObject().touch(aliceToken, name, null);
            assertEquals("Creating data object request failed", 200, res.getHttpStatusCode());
            assertEquals("Creating data object failed", 0,
                    getIrodsResponseStatusCode(res.getBody()));
        }

        // List only the contents of the home collection.
        Response listRes = alice.collections().list(aliceToken, homeCollection, null);
        assertEquals("Listing collections request failed", 200, listRes.getHttpStatusCode());

        JsonNode rootNode = assertDoesNotThrow(() -> mapper.readTree(listRes.getBody()),
                "JsonProcessingException was thrown");
        String entry = rootNode.path("entries").get(0).asText();
        assertEquals("/tempZone/home/alice/c0", entry);

        // List the home collection recursively.
        CollectionsListParams listParams = new CollectionsListParams();
        listParams.setRecurse(1);
        Response listRes2 = alice.collections().list(aliceToken, homeCollection, listParams);
        List<String> actualEntries = new ArrayList<>();

        rootNode = assertDoesNotThrow(() -> mapper.readTree(listRes2.getBody()),
                "JsonProcessingException was thrown");
        JsonNode entriesNode = rootNode.path("entries");

        for (JsonNode node : entriesNode) {
            actualEntries.add(node.asText());
        }
        Collections.sort(actualEntries);

        List<String> expectedEntries = new ArrayList<>();
        expectedEntries.add("/tempZone/home/alice/c0");
        expectedEntries.add("/tempZone/home/alice/c0/c1");
        expectedEntries.add("/tempZone/home/alice/c0/c1/c2");
        expectedEntries.add("/tempZone/home/alice/c0/c1/c2/d2");
        expectedEntries.add("/tempZone/home/alice/c0/c1/d1");
        expectedEntries.add("/tempZone/home/alice/c0/d0");
        Collections.sort(expectedEntries);
        assertEquals(expectedEntries, actualEntries);

        // Remove collections
        CollectionsRemoveParams removeParams = new CollectionsRemoveParams();
        removeParams.setRecurse(1);
        removeParams.setNoTrash(1);
        res = alice.collections().remove(aliceToken, "/tempZone/home/alice/c0", removeParams);
        assertEquals("Removing collection request failed", 200, res.getHttpStatusCode());
        assertEquals("Removing collection failed", 0,
                getIrodsResponseStatusCode(res.getBody()));
    }

    @Test
    public void testModifyingMetadataAtomically() {
        Response res;
        String collection = "/tempZone/home/alice";

        // Add metadata to the collection.
        List<ModifyMetadataOperations> operation = new ArrayList<>();
        operation.add(new ModifyMetadataOperations("add", "a1", "v1", "u1"));
        res = alice.collections().modify_metadata(aliceToken, collection, operation, OptionalInt.empty());
        assertEquals("Adding metadata to collection request failed", 200, res.getHttpStatusCode());
        assertEquals("Adding metadata to collection failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Show the metadata exists on the collection.
        String query = "select COLL_NAME where META_COLL_ATTR_NAME = 'a1' and META_COLL_ATTR_VALUE = 'v1' and " +
                "META_COLL_ATTR_UNITS = 'u1'";
        Response queryRes = alice.queryOperations().execute_genquery(aliceToken, query, null);
        assertEquals("Executing genquery request failed", 200, queryRes.getHttpStatusCode());
        assertEquals("Executing genquery failed", 0,
                getIrodsResponseStatusCode(queryRes.getBody()));

        JsonNode rootNode = assertDoesNotThrow(() -> mapper.readTree(queryRes.getBody()),
                "JsonProcessingException was thrown");
        JsonNode rowsNode = rootNode.path("rows");
        String firstRowFirstCol = rowsNode.get(0).get(0).asText();
        assertEquals(collection, firstRowFirstCol);

        // Remove the metadata from the collection.
        List<ModifyMetadataOperations> jsonParam2 = new ArrayList<>();
        jsonParam2.add(new ModifyMetadataOperations("remove", "a1", "v1", "u1"));
        res = alice.collections().modify_metadata(aliceToken, collection, jsonParam2, OptionalInt.empty());
        assertEquals("Removing metadata to collection request failed", 200, res.getHttpStatusCode());
        assertEquals("Removing metadata to collection failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Show the metadata no longer exists on the collection.
        // Same query as before
        Response queryRes2 = alice.queryOperations().execute_genquery(aliceToken, query, null);
        assertEquals("Executing genquery request failed", 200, queryRes2.getHttpStatusCode());
        assertEquals("Executing genquery failed", 0,
                getIrodsResponseStatusCode(queryRes2.getBody()));
        rootNode = assertDoesNotThrow(() -> mapper.readTree(queryRes2.getBody()),
                "JsonProcessingException was thrown");
        int rowsLength = rootNode.path("rows").size();
        assertEquals(0, rowsLength);
    }

    @Test
    public void testModifyingPermissionsAtomically() {
        Response res;
        String collection = "/tempZone/home/alice";

        // Give the rodsadmin read permission on the rodsuser's home collection.
        List<ModifyPermissionsOperations> operation = new ArrayList<>();
        operation.add(new ModifyPermissionsOperations("rods", "read"));
        res = assertDoesNotThrow(() ->
                alice.collections().modify_permissions(aliceToken, collection, operation, OptionalInt.empty()),
                "JsonProcessingException was thrown"
        );

        assertEquals("Modifying permissions on collection request failed", 200, res.getHttpStatusCode());
        assertEquals("Modifying permissions on collection failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Show the rodsadmin now has permission to read the collection
        Response statRes = alice.collections().stat(aliceToken, collection, Optional.empty());
        assertEquals("Stat on collection request failed", 200, statRes.getHttpStatusCode());
        assertEquals("Stat on collection failed", 0,
                getIrodsResponseStatusCode(statRes.getBody()));

        String name = null;
        String zone = null;
        String type = null;
        String perm = null;
        JsonNode rootNode = assertDoesNotThrow(() -> mapper.readTree(statRes.getBody()),
                "JsonProcessingException was thrown");
        JsonNode permissionsNode = rootNode.path("permissions");
        Iterator<JsonNode> elements = permissionsNode.elements();
        while (elements.hasNext()) {
            JsonNode element = elements.next();
            name = element.path("name").asText();
            if (name.equals("rods")) {
                zone = element.path("zone").asText();
                type = element.path("type").asText();
                perm = element.path("perm").asText();
            }
        }
        assertEquals("rods", name);
        assertEquals("tempZone", zone);
        assertEquals("rodsadmin", type);
        assertEquals("read_object", perm);

        // Remove rodsadmin's permission on the collection.
        List<ModifyPermissionsOperations> operation2 = new ArrayList<>();
        operation2.add(new ModifyPermissionsOperations("rods", "null"));
        res = assertDoesNotThrow(() ->
                        alice.collections().modify_permissions(aliceToken, collection, operation2, OptionalInt.empty()),
                "JsonProcessingException was thrown"
        );

        assertEquals("Modifying permissions on collection request failed", 200, res.getHttpStatusCode());
        assertEquals("Modifying permissions on collection failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Show the permissions have been removed.
        Response statRes2 = alice.collections().stat(aliceToken, collection, Optional.empty());
        assertEquals("Stat on collection request failed", 200, statRes2.getHttpStatusCode());
        assertEquals("Stat on collection failed", 0,
                getIrodsResponseStatusCode(statRes2.getBody()));

        rootNode = assertDoesNotThrow(() -> mapper.readTree(statRes2.getBody()),
                "JsonProcessingException was thrown");
        int permissionArraySize = rootNode.path("permissions").size();
        assertEquals(1, permissionArraySize);
    }

    @Test
    public void testTouchOperationUpdatesMtime() {
        Response res;
        String collection = "/tempZone/home/alice";

        // Get the mtime of the home collection.
        String query = "select COLL_MODIFY_TIME where COLL_NAME = '" + collection + "'";
        Response queryRes = alice.queryOperations().execute_genquery(aliceToken, query, null);
        assertEquals("Execute genquery request failed", 200, queryRes.getHttpStatusCode());
        assertEquals("Execute genquery failed", 0,
                getIrodsResponseStatusCode(queryRes.getBody()));

        JsonNode rootNode = assertDoesNotThrow(() -> mapper.readTree(queryRes.getBody()),
                "JsonProcessingException was thrown");
        int rowsArraySize = rootNode.path("rows").size();
        int originamMtime = rootNode.path("rows").get(0).get(0).asInt();

        assertEquals(1, rowsArraySize);
        assert(originamMtime > 0);

        // Sleep for a short period of time to guarantee a difference in the mtime.
        assertDoesNotThrow(() -> Thread.sleep(2000), "An interruption occurred while sleeping.");

        // Update the mtime by calling touch.
        res =  alice.collections().touch(aliceToken, collection, null);
        assertEquals("Touch request failed", 200, res.getHttpStatusCode());
        assertEquals("Touch failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Show the mtime has been updated.
        // Same query as before.
        Response queryRes2 = alice.queryOperations().execute_genquery(aliceToken, query, null);
        assertEquals("Execute genquery request failed", 200, queryRes2.getHttpStatusCode());
        assertEquals("Execute genquery failed", 0,
                getIrodsResponseStatusCode(queryRes2.getBody()));

        rootNode = assertDoesNotThrow(() -> mapper.readTree(queryRes2.getBody()),
                "JsonProcessingException was thrown");
        rowsArraySize = rootNode.path("rows").size();
        int newMtime = rootNode.path("rows").get(0).get(0).asInt();
        assertEquals(1, rowsArraySize);
        assert(newMtime > originamMtime);
    }

    @Test
    public void testEnablingAndDisablingInheritance() {
        Response res;
        String collection = "/tempZone/home/alice";

        // Show inheritance is not enabled.
        Response statRes = alice.collections().stat(aliceToken, collection, Optional.empty());
        assertEquals("Stat on collection request failed", 200, statRes.getHttpStatusCode());
        assertEquals("Stat on collection failed", 0,
                getIrodsResponseStatusCode(statRes.getBody()));

        JsonNode rootNode = assertDoesNotThrow(() -> mapper.readTree(statRes.getBody()),
                "JsonProcessingException was thrown");
        boolean inheritanceEnabled = rootNode.path("inheritance_enabled").asBoolean();
        assertFalse(inheritanceEnabled);

        // Enable inheritance.
        res = alice.collections().set_inheritance(aliceToken, collection, 1, OptionalInt.empty());
        assertEquals("Set inheritance request failed", 200, res.getHttpStatusCode());
        assertEquals("Set inheritance failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Show inheritance is enabled.
        Response statRes2 = alice.collections().stat(aliceToken, collection, Optional.empty());
        assertEquals("Stat on collection request failed", 200, statRes2.getHttpStatusCode());
        assertEquals("Stat on collection failed", 0,
                getIrodsResponseStatusCode(statRes2.getBody()));
        rootNode = assertDoesNotThrow(() -> mapper.readTree(statRes2.getBody()),
                "JsonProcessingException was thrown");
        inheritanceEnabled = rootNode.path("inheritance_enabled").asBoolean();
        assertTrue(inheritanceEnabled);

        // Disable inheritance.
        res = alice.collections().set_inheritance(aliceToken, collection, 0, OptionalInt.empty());
        assertEquals("Set inheritance request failed", 200, res.getHttpStatusCode());
        assertEquals("Set inheritance failed", 0,
                getIrodsResponseStatusCode(res.getBody()));

        // Show inheritance is not enabled.
        Response statRes3 = alice.collections().stat(aliceToken, collection, Optional.empty());
        assertEquals("Stat on collection request failed", 200, statRes3.getHttpStatusCode());
        assertEquals("Stat on collection failed", 0,
                getIrodsResponseStatusCode(statRes3.getBody()));

        rootNode = assertDoesNotThrow(() -> mapper.readTree(statRes3.getBody()),
                "JsonProcessingException was thrown");
        inheritanceEnabled = rootNode.path("inheritance_enabled").asBoolean();
        assertFalse(inheritanceEnabled);
    }

    private int getIrodsResponseStatusCode(String jsonString) {
        JsonNode rootNode = assertDoesNotThrow(() -> mapper.readTree(jsonString),
                "JsonProcessingException was thrown");
        JsonNode statusCodeNode = rootNode.path("irods_response").path("status_code");
        return statusCodeNode.asInt();
    }

    private void resetPath(Wrapper wrapper, String token, String lpath) {
        Response res;
        res = wrapper.collections().list(token, lpath, null);

        List<String> entries = new ArrayList<>();
        JsonNode rootNode = assertDoesNotThrow(() -> mapper.readTree(res.getBody()),
                "JsonProcessingException was thrown");
        JsonNode entriesNode = rootNode.path("entries");

        for (JsonNode node : entriesNode) {
            entries.add(node.asText());
        }

        if (entries != null && !entries.isEmpty()) {
            for (String entry : entries) {
                wrapper.collections().remove(token, entry, null);
                wrapper.dataObject().remove(token, entry, 0, null);
                wrapper.resourceOperations().remove(token, entry);
            }
        }
    }
}