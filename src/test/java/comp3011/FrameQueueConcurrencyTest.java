
package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

public class FrameQueueConcurrencyTest {

    private static final int FRAME_BUFFER_CAPACITY = 3;

    @Test
    void testQueueCapacityIsThree() {
        ArrayBlockingQueue<Integer> queue =
                new ArrayBlockingQueue<>(FRAME_BUFFER_CAPACITY);

        assertTrue(queue.offer(1));
        assertTrue(queue.offer(2));
        assertTrue(queue.offer(3));

        assertEquals(3, queue.size());
        assertEquals(0, queue.remainingCapacity());
    }

    @Test
    void testQueueCannotExceedCapacity() {
        ArrayBlockingQueue<Integer> queue =
                new ArrayBlockingQueue<>(FRAME_BUFFER_CAPACITY);

        queue.offer(1);
        queue.offer(2);
        queue.offer(3);

        assertFalse(queue.offer(4));
        assertEquals(3, queue.size());
    }

    @Test
    void testFramesRemainInOrder() {
        ArrayBlockingQueue<Integer> queue =
                new ArrayBlockingQueue<>(FRAME_BUFFER_CAPACITY);

        queue.offer(10);
        queue.offer(20);
        queue.offer(30);

        assertEquals(10, queue.poll());
        assertEquals(20, queue.poll());
        assertEquals(30, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    void testRemovingFrameMakesSpace() {
        ArrayBlockingQueue<Integer> queue =
                new ArrayBlockingQueue<>(FRAME_BUFFER_CAPACITY);

        queue.offer(1);
        queue.offer(2);
        queue.offer(3);

        assertEquals(1, queue.poll());
        assertTrue(queue.offer(4));

        assertEquals(List.of(2, 3, 4), new ArrayList<>(queue));
    }

    @Test
    void testProducerWaitsWhenQueueIsFull() throws Exception {
        ArrayBlockingQueue<Integer> queue =
                new ArrayBlockingQueue<>(FRAME_BUFFER_CAPACITY);

        queue.put(1);
        queue.put(2);
        queue.put(3);

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CountDownLatch started = new CountDownLatch(1);

            Future<?> producer = executor.submit(() -> {
                started.countDown();
                queue.put(4);
                return null;
            });

            assertTrue(started.await(2, TimeUnit.SECONDS));

            // The producer should still be waiting for buffer space.
            assertFalse(producer.isDone());

            assertEquals(1, queue.take());

            // Removing a frame should allow the producer to continue.
            producer.get(2, TimeUnit.SECONDS);

            assertEquals(List.of(2, 3, 4), new ArrayList<>(queue));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void testWaitingProducerCanBeInterrupted() throws Exception {
        ArrayBlockingQueue<Integer> queue =
                new ArrayBlockingQueue<>(FRAME_BUFFER_CAPACITY);

        queue.put(1);
        queue.put(2);
        queue.put(3);

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CountDownLatch started = new CountDownLatch(1);

            Future<Boolean> producer = executor.submit(() -> {
                started.countDown();

                try {
                    queue.put(4);
                    return false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            });

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(producer.isDone());

            // Interrupt the waiting producer and shut down the executor.
            executor.shutdownNow();

            // Confirm the producer handled interruption correctly.
            assertTrue(producer.get(5, TimeUnit.SECONDS));
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            // The interrupted producer must not add another frame.
            assertEquals(3, queue.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void testConcurrentProducerConsumerLoad() throws Exception {
        final int frameCount = 1000;

        ArrayBlockingQueue<Integer> queue =
                new ArrayBlockingQueue<>(FRAME_BUFFER_CAPACITY);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> producer = executor.submit(() -> {
                for (int i = 0; i < frameCount; i++) {
                    queue.put(i);
                }
                return null;
            });

            Future<List<Integer>> consumer = executor.submit(() -> {
                List<Integer> received = new ArrayList<>();

                for (int i = 0; i < frameCount; i++) {
                    received.add(queue.take());
                }

                return received;
            });

            producer.get(10, TimeUnit.SECONDS);
            List<Integer> received = consumer.get(10, TimeUnit.SECONDS);

            assertEquals(frameCount, received.size());
            assertTrue(queue.isEmpty());

            for (int i = 0; i < frameCount; i++) {
                assertEquals(i, received.get(i));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void testMultipleProducersAndConsumers() throws Exception {
        final int framesPerProducer = 500;
        final int producerCount = 2;
        final int totalFrames = framesPerProducer * producerCount;

        ArrayBlockingQueue<Integer> queue =
                new ArrayBlockingQueue<>(FRAME_BUFFER_CAPACITY);

        ExecutorService executor = Executors.newFixedThreadPool(4);

        try {
            List<Future<?>> producers = new ArrayList<>();

            for (int producerId = 0; producerId < producerCount; producerId++) {
                final int id = producerId;

                producers.add(executor.submit(() -> {
                    for (int i = 0; i < framesPerProducer; i++) {
                        queue.put(id * framesPerProducer + i);
                    }
                    return null;
                }));
            }

            List<Future<List<Integer>>> consumers = new ArrayList<>();

            for (int consumerId = 0; consumerId < 2; consumerId++) {
                consumers.add(executor.submit(() -> {
                    List<Integer> received = new ArrayList<>();

                    for (int i = 0; i < totalFrames / 2; i++) {
                        received.add(queue.take());
                    }

                    return received;
                }));
            }

            for (Future<?> producer : producers) {
                producer.get(10, TimeUnit.SECONDS);
            }

            Set<Integer> receivedFrames = new HashSet<>();

            for (Future<List<Integer>> consumer : consumers) {
                for (Integer frame : consumer.get(10, TimeUnit.SECONDS)) {
                    assertTrue(receivedFrames.add(frame),
                            "Duplicate frame: " + frame);
                }
            }

            assertEquals(totalFrames, receivedFrames.size());
            assertTrue(queue.isEmpty());

            for (int i = 0; i < totalFrames; i++) {
                assertTrue(receivedFrames.contains(i),
                        "Missing frame: " + i);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
