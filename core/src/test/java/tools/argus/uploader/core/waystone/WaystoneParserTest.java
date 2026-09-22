package tools.argus.uploader.core.waystone;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaystoneParserTest {

    @Test
    void readsTheDocumentedWaystoneList() {
        List<Waystone> list = WaystoneParser.waystones("""
                { "waystones": [
                  { "name": "Group", "label": "Group Project", "x": -7776, "y": 64, "z": -2440, "dim": "overworld" },
                  { "name": "Hub", "label": "", "x": 8, "y": 70, "z": 16, "dim": "the_nether" }
                ] }""");

        assertEquals(2, list.size());
        Waystone group = list.get(0);
        assertEquals("Group", group.name());
        assertEquals("Group Project", group.displayName());
        assertEquals(-7776, group.x());
        assertEquals(-2440, group.z());
        assertEquals("Overworld", group.dimensionLabel());
        assertEquals("Hub", list.get(1).displayName(), "an empty label falls back to the name");
        assertEquals("Nether", list.get(1).dimensionLabel());
    }

    @Test
    void skipsWaystonesItCannotUseAndDefaultsTheRest() {
        List<Waystone> list = WaystoneParser.waystones("""
                { "waystones": [
                  { "label": "no name", "x": 1, "z": 1 },
                  { "name": "nocoords" },
                  { "name": "far", "x": 999999999, "z": 0 },
                  "junk", 7, null,
                  { "name": "ok", "x": 5, "z": 6, "dim": "weird" }
                ] }""");

        assertEquals(1, list.size());
        assertEquals("overworld", list.get(0).dimension(), "an unknown dimension is treated as the overworld");
        assertEquals(64, list.get(0).y(), "a missing y gets a default");
    }

    @Test
    void rejectsRepliesThatAreNotTheList() {
        assertThrows(IllegalArgumentException.class, () -> WaystoneParser.waystones("not json"));
        assertThrows(IllegalArgumentException.class, () -> WaystoneParser.waystones("[]"));
        assertThrows(IllegalArgumentException.class, () -> WaystoneParser.waystones("{\"error\":\"x\"}"));
    }

    @Test
    void readsTheBotStatusTokenInfoAndTeleportReplies() {
        SystemStatus status = WaystoneParser.systemStatus("{ \"online\": true, \"busy\": false, \"queued\": 2, \"secondsUntilReady\": 45 }");
        assertTrue(status.online());
        assertFalse(status.busy());
        assertEquals(2, status.queued());
        assertEquals(45, status.secondsUntilReady());
        assertTrue(status.bots().isEmpty(), "no bot name unless ARGUS gives one");

        TokenInfo info = WaystoneParser.tokenInfo(
                "{ \"ok\": true, \"ign\": \"Wraith_k25\", \"balance\": 8, \"earned\": 10, \"spent\": 2, \"regions\": 50, \"regionsPerToken\": 5 }");
        assertEquals("Wraith_k25", info.ign());
        assertEquals(8, info.balance());
        assertEquals(5, info.regionsPerToken());

        WaystoneParser.Started started = WaystoneParser.started(
                "{ \"ok\": true, \"id\": \"abc123\", \"waystone\": \"Group\", \"ign\": \"Wraith_k25\", \"balance\": 7 }");
        assertEquals("abc123", started.id());
        assertEquals(7, started.balance());
    }

    @Test
    void readsTeleportProgressIncludingTheBotNameWhenItIsThere() {
        TeleportStatus queued = WaystoneParser.teleportStatus(
                "{ \"status\": \"queued\", \"waystone\": \"Group\", \"position\": 3, \"error\": null }");
        assertEquals("queued", queued.status());
        assertEquals(3, queued.position());
        assertNull(queued.error());
        assertEquals("", queued.bot());
        assertFalse(queued.isReady());
        assertFalse(queued.isFinished());

        TeleportStatus ready = WaystoneParser.teleportStatus("{ \"status\": \"ready\", \"waystone\": \"Group\", \"botIgn\": \"mun_bot\" }");
        assertTrue(ready.isReady());
        assertEquals("mun_bot", ready.bot());

        assertTrue(WaystoneParser.teleportStatus("{\"status\":\"delivered\"}").isFinished());
        assertTrue(WaystoneParser.teleportStatus("{\"status\":\"failed\",\"error\":\"bot died\"}").isFinished());
        assertTrue(WaystoneParser.teleportStatus("{\"status\":\"expired\"}").isFinished());
        assertEquals("mun2", WaystoneParser.teleportStatus("{\"status\":\"ready\",\"bot\":\"mun2\"}").bot());
    }

    @Test
    void readsTheBotNamesWhenAReplyGivesThem() {
        assertEquals(List.of("mun_bot"), WaystoneParser.systemStatus("{\"online\":true,\"botIgn\":\"mun_bot\"}").bots());
        assertEquals(List.of("a_bot", "b_bot"),
                WaystoneParser.systemStatus("{\"online\":true,\"bots\":[\"a_bot\",\"b_bot\",\"a_bot\"]}").bots());
        assertEquals(List.of("one", "two"), WaystoneParser.systemStatus(
                "{\"online\":true,\"bots\":[{\"ign\":\"one\"},{\"name\":\"two\"}]}").bots());
        assertEquals(List.of("good_one"), WaystoneParser.systemStatus(
                "{\"online\":true,\"bots\":[\"bad name\",\"/op me\",\"good_one\",7,null]}").bots(),
                "anything that isn't a plain username is dropped");
        assertEquals("mun_bot", WaystoneParser.teleportStatus("{\"status\":\"ready\",\"bots\":[\"mun_bot\"]}").bot());
        assertEquals("", WaystoneParser.teleportStatus("{\"status\":\"ready\",\"botIgn\":\"not valid!\"}").bot());
    }

    @Test
    void readsTheConfirmedApiExamplesVerbatim() {
        SystemStatus status = WaystoneParser.systemStatus(
                "{ \"online\": true, \"busy\": false, \"queued\": 0, \"secondsUntilReady\": 0, \"bots\": [\"mun_map\"] }");
        assertEquals(List.of("mun_map"), status.bots());

        TeleportStatus queued = WaystoneParser.teleportStatus(
                "{ \"status\": \"queued\", \"waystone\": \"Group\", \"position\": 0, \"bot\": \"mun_map\", \"error\": null }");
        assertEquals("mun_map", queued.bot(), "the assigned bot is present from \"queued\" onward, not only at \"ready\"");
    }

    @Test
    void readsErrorCodesAndBalances() {
        assertEquals("insufficient_tokens", WaystoneParser.errorCode("{\"error\":\"insufficient_tokens\",\"balance\":0}"));
        assertEquals(0, WaystoneParser.balanceOf("{\"error\":\"insufficient_tokens\",\"balance\":0}"));
        assertNull(WaystoneParser.errorCode("<html>"));
        assertEquals(-1, WaystoneParser.balanceOf("<html>"));
    }

    @Test
    void onlyARealUsernameEverBecomesATpaCommand() {
        assertEquals(Optional.of("tpa mun_bot"), BotName.tpaCommand(" mun_bot "));
        assertEquals(Optional.of("tpa Wraith_k25"), BotName.tpaCommand("Wraith_k25"));
        assertTrue(BotName.tpaCommand("mun; /op me").isEmpty());
        assertTrue(BotName.tpaCommand("mun bot").isEmpty());
        assertTrue(BotName.tpaCommand("/tpa").isEmpty());
        assertTrue(BotName.tpaCommand("ab").isEmpty());
        assertTrue(BotName.tpaCommand("waytoolongusernamehere").isEmpty());
        assertTrue(BotName.tpaCommand("").isEmpty());
        assertTrue(BotName.tpaCommand(null).isEmpty());
        assertTrue(BotName.tpaCommand("mun\n/op").isEmpty());
    }
}
