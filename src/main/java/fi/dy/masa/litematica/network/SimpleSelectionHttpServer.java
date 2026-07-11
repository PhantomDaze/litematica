package fi.dy.masa.litematica.network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelectionSimple;
import fi.dy.masa.litematica.selection.Box;

public class SimpleSelectionHttpServer
{
    private static final SimpleSelectionHttpServer INSTANCE = new SimpleSelectionHttpServer();
    private static final String PATH = "/litematica/simple-selection";
    private static final int PORT = 38080;
    private static final String HOST = "127.0.0.1";

    @Nullable private ServerSocket serverSocket;
    @Nullable private Thread acceptThread;
    private volatile boolean running;

    public static SimpleSelectionHttpServer getInstance()
    {
        return INSTANCE;
    }

    public synchronized void start()
    {
        if (this.running)
        {
            return;
        }

        try
        {
            this.serverSocket = new ServerSocket(PORT, 50, InetAddress.getByName(HOST));
            this.running = true;
            this.acceptThread = new Thread(this::acceptLoop, "litematica-simple-selection-http");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();

            Litematica.LOGGER.info("Simple selection HTTP endpoint started at http://{}:{}{}", HOST, PORT, PATH);
        }
        catch (Exception e)
        {
            this.running = false;
            this.serverSocket = null;
            this.acceptThread = null;
            Litematica.LOGGER.error("Failed to start simple selection HTTP endpoint", e);
        }
    }

    public synchronized void stop()
    {
        this.running = false;

        if (this.serverSocket != null)
        {
            try
            {
                this.serverSocket.close();
            }
            catch (Exception ignore)
            {
            }

            this.serverSocket = null;
        }

        this.acceptThread = null;
    }

    private void acceptLoop()
    {
        while (this.running)
        {
            ServerSocket ss = this.serverSocket;

            if (ss == null)
            {
                break;
            }

            try (Socket socket = ss.accept())
            {
                this.handleConnection(socket);
            }
            catch (Exception e)
            {
                if (this.running)
                {
                    Litematica.LOGGER.warn("Simple selection HTTP accept failed", e);
                }
            }
        }
    }

    private void handleConnection(Socket socket) throws IOException
    {
        socket.setSoTimeout(2000);

        try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        )
        {
            String requestLine = reader.readLine();

            if (requestLine == null || requestLine.isEmpty())
            {
                return;
            }

            String[] parts = requestLine.split(" ");

            if (parts.length < 2)
            {
                writeResponse(writer, 400, "{\"error\":\"bad_request\"}");
                return;
            }

            String method = parts[0];
            String path = parts[1];

            // Consume and ignore headers.
            String line;
            while ((line = reader.readLine()) != null && line.isEmpty() == false)
            {
            }

            if ("GET".equalsIgnoreCase(method) == false)
            {
                writeResponse(writer, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }

            if (PATH.equals(path) == false)
            {
                writeResponse(writer, 404, "{\"error\":\"not_found\"}");
                return;
            }

            JsonObject obj = this.buildSelectionJson();
            writeResponse(writer, 200, obj.toString());
        }
    }

    private JsonObject buildSelectionJson()
    {
        JsonObject obj = new JsonObject();
        AreaSelectionSimple area = DataManager.getSimpleArea();
        Box box = area.getSelectedSubRegionBox();

        obj.addProperty("mode", "simple");
        obj.addProperty("name", area.getName());

        if (box != null)
        {
            obj.add("pos1", toPosJson(box.getPos1()));
            obj.add("pos2", toPosJson(box.getPos2()));
            obj.add("size", toPosJson(box.getSize()));
        }
        else
        {
            obj.add("pos1", JsonNull.INSTANCE);
            obj.add("pos2", JsonNull.INSTANCE);
            obj.add("size", JsonNull.INSTANCE);
        }

        obj.add("effectiveOrigin", toPosJson(area.getEffectiveOrigin()));

        BlockPos explicitOrigin = area.getExplicitOrigin();
        obj.add("explicitOrigin", explicitOrigin != null ? toPosJson(explicitOrigin) : JsonNull.INSTANCE);
        obj.addProperty("hasExplicitOrigin", explicitOrigin != null);

        Level level = Minecraft.getInstance().level;
        obj.addProperty("dimension", level != null ? level.dimension().identifier().toString() : "none");

        return obj;
    }

    private static JsonObject toPosJson(@Nullable BlockPos pos)
    {
        JsonObject obj = new JsonObject();

        if (pos == null)
        {
            return obj;
        }

        obj.addProperty("x", pos.getX());
        obj.addProperty("y", pos.getY());
        obj.addProperty("z", pos.getZ());
        return obj;
    }

    private static void writeResponse(BufferedWriter writer, int status, String body) throws IOException
    {
        String text = switch (status)
        {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> "Error";
        };

        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        writer.write("HTTP/1.1 " + status + " " + text + "\r\n");
        writer.write("Content-Type: application/json; charset=utf-8\r\n");
        writer.write("Content-Length: " + bodyBytes.length + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.write(body);
        writer.flush();
    }
}
