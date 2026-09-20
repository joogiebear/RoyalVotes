package com.mystipixel.royalvotes.net;

import com.vexsoftware.votifier.model.Vote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listens for vote sites.
 *
 * <p>Plain blocking sockets on a small bounded pool, not Netty: a busy server sees a few votes a
 * minute, and the port is reachable by anyone on the internet, so the things that matter are a short
 * read timeout and a hard cap on concurrent connections — both of which are one line each here.
 */
public final class VoteServer {

    private static final int READ_TIMEOUT_MS = 5000;

    private final Logger logger;
    private final String host;
    private final int port;
    private final PrivateKey rsaKey;
    private final Function<String, String> tokenLookup;
    private final Consumer<Vote> onVote;
    private final boolean debug;
    private final SecureRandom random = new SecureRandom();

    private ServerSocket socket;
    private ThreadPoolExecutor workers;
    private volatile boolean running;

    /**
     * @param rsaKey null refuses v1 votes
     * @param onVote called on a worker thread — the receiver hops to the main thread itself
     */
    public VoteServer(Logger logger, String host, int port, PrivateKey rsaKey,
                      Function<String, String> tokenLookup, Consumer<Vote> onVote, boolean debug) {
        this.logger = logger;
        this.host = host;
        this.port = port;
        this.rsaKey = rsaKey;
        this.tokenLookup = tokenLookup;
        this.onVote = onVote;
        this.debug = debug;
    }

    public void start() throws IOException {
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(host), port));
        running = true;

        // 4 workers, 16 waiting; past that, connections are dropped rather than queued without limit.
        workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "RoyalVotes-connection");
            thread.setDaemon(true);
            return thread;
        });
        workers.allowCoreThreadTimeOut(true);

        Thread acceptor = new Thread(this::acceptLoop, "RoyalVotes-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public void stop() {
        running = false;
        if (socket != null) {
            try {
                socket.close();                  // unblocks accept()
            } catch (IOException ignored) {
            }
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    public boolean isRunning() {
        return running && socket != null && !socket.isClosed();
    }

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException closed) {
                if (running) {
                    logger.log(Level.WARNING, "Vote listener stopped accepting connections", closed);
                }
                return;
            }
            try {
                workers.execute(() -> handle(client));
            } catch (RejectedExecutionException saturated) {
                closeQuietly(client);
            }
        }
    }

    private void handle(Socket client) {
        String remote = String.valueOf(client.getRemoteSocketAddress());
        try (client) {
            client.setSoTimeout(READ_TIMEOUT_MS);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String challenge = new BigInteger(130, random).toString(32);
            out.write(VoteProtocol.greeting(challenge).getBytes(StandardCharsets.UTF_8));
            out.flush();

            Vote vote = VoteProtocol.read(in, out, challenge, rsaKey, tokenLookup);
            onVote.accept(vote);
        } catch (VoteProtocol.VoteException rejected) {
            // Port scanners hit this constantly, so it is only worth a line when someone is debugging
            // a vote site that will not connect.
            if (debug) {
                logger.warning("Rejected vote from " + remote + ": " + rejected.getMessage());
            }
        } catch (IOException dropped) {
            if (debug) {
                logger.warning("Connection from " + remote + " dropped: " + dropped.getMessage());
            }
        } catch (RuntimeException unexpected) {
            logger.log(Level.WARNING, "Unexpected error handling a vote from " + remote, unexpected);
        }
    }

    private static void closeQuietly(Socket client) {
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }
}
