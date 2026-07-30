package gearth.app.services.packet_info.providers.implementations;

import gearth.app.GEarth;
import gearth.protocol.HMessage;
import gearth.services.packet_info.PacketInfo;
import gearth.app.services.packet_info.providers.PacketInfoProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public class GEarthUnityPacketInfoProvider extends PacketInfoProvider {

    private static final Logger LOG = LoggerFactory.getLogger(GEarthUnityPacketInfoProvider.class);

    private final String host;

    public GEarthUnityPacketInfoProvider(String hotelVersion) {
        this(hotelVersion, null);
    }

    public GEarthUnityPacketInfoProvider(String hotelVersion, String host) {
        super(hotelVersion);
        this.host = host;
    }

    @Override
    protected File getFile() {
        try {
            // Prefer the explicit data dir (set e.g. by an in-process/jpackage launcher
            // whose jar no longer sits next to the app data); fall back to the code-source
            // parent for the classic launcher layout.
            final String dataDir = System.getProperty("gearth.data.dir");
            final File base = (dataDir != null && !dataDir.isEmpty())
                    ? new File(dataDir)
                    : new File(GEarth.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
            // Per-retro packet names: a Nitro retro can ship its own list at
            // <dataDir>/messages/<host>.json so several retros don't share one file.
            // Fall back to the shared messages.json when there is no per-retro list.
            if (host != null && !host.isEmpty()) {
                final File perRetro = new File(new File(base, "messages"), sanitizeHost(host) + ".json");
                if (perRetro.isFile()) {
                    LOG.info("Using per-retro packet names for host '{}': {}", host, perRetro);
                    return perRetro;
                }
            }
            return new File(base, "messages.json");
        } catch (URISyntaxException e) {
            LOG.error("Could not find messages.json file", e);
            return null;
        }
    }

    private static String sanitizeHost(String host) {
        return host.trim().toLowerCase().replaceAll("[^a-z0-9._-]", "_");
    }

    private PacketInfo jsonToPacketInfo(JSONObject object, HMessage.Direction destination) {
        String name = object.getString("Name");
        int headerId = object.getInt("Id");
        return new PacketInfo(destination, headerId, null, name, null, "G-Earth");
    }

    @Override
    protected List<PacketInfo> parsePacketInfo(JSONObject jsonObject) {
        List<PacketInfo> packetInfos = new ArrayList<>();

        try {
            JSONArray incoming = jsonObject.getJSONArray("Incoming");
            JSONArray outgoing = jsonObject.getJSONArray("Outgoing");

            if (incoming != null && outgoing != null) {
                for (int i = 0; i < incoming.length(); i++) {
                    JSONObject jsonInfo = incoming.getJSONObject(i);
                    PacketInfo packetInfo = jsonToPacketInfo(jsonInfo, HMessage.Direction.TOCLIENT);
                    packetInfos.add(packetInfo);
                }
                for (int i = 0; i < outgoing.length(); i++) {
                    JSONObject jsonInfo = outgoing.getJSONObject(i);
                    PacketInfo packetInfo = jsonToPacketInfo(jsonInfo, HMessage.Direction.TOSERVER);
                    packetInfos.add(packetInfo);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return packetInfos;
    }
}
