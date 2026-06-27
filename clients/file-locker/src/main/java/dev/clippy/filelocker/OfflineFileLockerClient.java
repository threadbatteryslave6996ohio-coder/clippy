package dev.clippy.filelocker;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

public final class OfflineFileLockerClient {
    public static final Path DEFAULT_SOCKET_PATH = Path.of("/tmp/clippy-offline-file-locker.sock");

    private final UnixDomainSocketAddress address;

    public OfflineFileLockerClient(Path socketPath) {
        this.address = UnixDomainSocketAddress.of(socketPath.toAbsolutePath().normalize());
    }

    public String read(Path path) throws IOException {
        return request(FileLockerProtocol.READ, path, null);
    }

    public void ping() throws IOException {
        request(FileLockerProtocol.PING, Path.of("."), null);
    }

    public void append(Path path, String jsonEntry) throws IOException {
        request(FileLockerProtocol.APPEND, path, jsonEntry);
    }

    private String request(int operation, Path path, String content) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
            output.writeByte(operation);
            FileLockerProtocol.writeString(output, path.toAbsolutePath().normalize().toString());
            if (content != null) {
                FileLockerProtocol.writeString(output, content);
            }
            output.flush();

            DataInputStream input = new DataInputStream(Channels.newInputStream(channel));
            int status = input.readUnsignedByte();
            String response = FileLockerProtocol.readString(input);
            if (status != FileLockerProtocol.OK) {
                throw new IOException("File-locker service rejected the request: " + response);
            }
            return response;
        } catch (java.net.ConnectException exception) {
            throw new IOException("Cannot connect to file-locker service at " + address.getPath()
                    + ". Start scripts/start-file-locker.sh first.", exception);
        }
    }
}
