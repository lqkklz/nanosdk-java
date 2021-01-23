package io.sisu.nng;

import io.sisu.nng.aio.Aio;
import io.sisu.nng.aio.AioCallback;
import io.sisu.nng.aio.AioProxy;
import io.sisu.nng.aio.ContextProxy;
import io.sisu.nng.internal.ContextStruct;
import io.sisu.nng.internal.NngOptions;
import io.sisu.nng.internal.SocketStruct;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Wrapper of an NNG context, allowing for multi-threaded use of Sockets.
 *
 * This wrapper is pretty basic at the moment and effectively works synchronously.
 */
public class Context {
    private final Socket socket;
    private final ContextStruct.ByValue context;
    private final Aio aio;

    private final BlockingQueue<Work> queue = new ArrayBlockingQueue<>(10);
    private final HashMap<String, Object> stateMap = new HashMap<>();

    private final Map<Event, BiConsumer<ContextProxy, Message>> eventHandlers = new HashMap<>();
    private static final BiConsumer<ContextProxy, Message> noop = (a, m) -> {};

    public enum Event {
        RECV,
        SEND,
        WAKE,
    }

    public static class Work {
        public final Event event;
        public final CompletableFuture<Object> future;

        public Work(Event event, CompletableFuture<Object> future) {
            this.event = event;
            this.future = future;
        }
    }

    private final ContextProxy proxy = new ContextProxy() {
        @Override
        public void send(Message message) {
            sendMessage(message);
        }

        @Override
        public void receive() {
            receiveMessage();
        }

        @Override
        public void sleep(int millis) {
            Context.this.sleep(millis);
        }

        @Override
        public void put(String key, Object value) {
            stateMap.put(key, value);
        }

        @Override
        public Object get(String key) {
            return stateMap.get(key);
        }

        @Override
        public Object getOrDefault(Object key, Object defaultValue) {
            return stateMap.getOrDefault(key, defaultValue);
        }
    };

    private static void dispatch(AioProxy aioProxy, Context ctx) {
        try {
            Work work = ctx.queue.poll(1000, TimeUnit.MILLISECONDS);
            if (work == null) {
                // XXX: no known work
                return;
            }

            try {
                aioProxy.assertSuccessful();
                BiConsumer<ContextProxy, Message> consumer = ctx.eventHandlers.getOrDefault(work.event, noop);
                Object result = null;

                switch (work.event) {
                    case RECV:
                        result = aioProxy.getMessage();
                        break;
                    case SEND:
                    case WAKE:
                        break;
                }

                consumer.accept(ctx.proxy, (Message) result);

                if (work.future != null) {
                    work.future.complete(result);
                }
            } catch (NngException e) {
                if (work.future != null) {
                    work.future.completeExceptionally(e);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Context(Socket socket) throws NngException {
        ContextStruct contextStruct = new ContextStruct();
        SocketStruct.ByValue socketStruct = (SocketStruct.ByValue) socket.getSocketStruct();

        int rv = Nng.lib().nng_ctx_open(contextStruct, socketStruct);
        if (rv != 0) {
            throw new NngException(Nng.lib().nng_strerror(rv));
        }

        this.socket = socket;
        this.context = new ContextStruct.ByValue(contextStruct);
        this.aio = new Aio(this, new AioCallback<>(Context::dispatch, this));
    }

    public void setRecvHandler(BiConsumer<ContextProxy, Message> handler) {
        this.eventHandlers.put(Event.RECV, handler);
    }

    public void setSendHandler(Consumer<ContextProxy> handler) {
        this.eventHandlers.put(Event.SEND, (aioProxy, unused) -> handler.accept(aioProxy));
    }

    public void setWakeHandler(Consumer<ContextProxy> handler) {
        this.eventHandlers.put(Event.WAKE, (aioProxy, unused) -> handler.accept(aioProxy));
    }

    public void close() throws NngException {
        int rv = Nng.lib().nng_ctx_close(context);
        if (rv != 0) {
            throw new NngException(Nng.lib().nng_strerror(rv));
        }

        Nng.lib().nng_aio_free(aio.getAioPointer());
    }

    public CompletableFuture<Message> receiveMessage() {
        CompletableFuture<Object> future = new CompletableFuture<>();

        this.queue.add(new Work(Event.RECV, future));
        Nng.lib().nng_ctx_recv(context, aio.getAioPointer());

        return future.thenApply(obj -> (Message) obj);
    }

    public Message receiveMessageSync() throws NngException {
        try {
            return receiveMessage().get();
        } catch (InterruptedException e) {
            throw  new NngException("interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof NngException) {
                throw (NngException) e.getCause();
            } else {
                throw new NngException("unknown execution exception");
            }
        }
    }

    public CompletableFuture<Void> sendMessage(Message msg) {
        CompletableFuture<Object> future = new CompletableFuture<>();

        this.queue.add(new Work(Event.SEND, future));
        aio.setMessage(msg);
        Nng.lib().nng_ctx_send(context, aio.getAioPointer());

        return future.thenApply((unused) -> null);
    }

    public void sendMessageSync(Message msg) throws NngException {
        try {
            sendMessage(msg).join();
        } catch (Exception e) {
            if (e.getCause() instanceof NngException) {
                throw (NngException) e.getCause();
            } else {
                throw new NngException("unknown execution exception");
            }
        }
    }

    public CompletableFuture<Void> sleep(int millis) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        this.queue.add(new Work(Event.WAKE, future));
        aio.sleep(millis);
        return future.thenApply((unused) -> null);
    }

    public void setReceiveTimeout(int timeoutMillis) throws NngException {
        int rv = Nng.lib().nng_ctx_set_ms(this.context, NngOptions.RECV_TIMEOUT, timeoutMillis);
        if (rv != 0) {
            String err = Nng.lib().nng_strerror(rv);
            throw new NngException(err);
        }
    }

    public void setSendTimeout(int timeoutMillis) throws NngException {
        int rv = Nng.lib().nng_ctx_set_ms(this.context, NngOptions.SEND_TIMEOUT, timeoutMillis);
        if (rv != 0) {
            String err = Nng.lib().nng_strerror(rv);
            throw new NngException(err);
        }
    }

    public ContextStruct.ByValue getContextStruct() {
        return context;
    }
}
