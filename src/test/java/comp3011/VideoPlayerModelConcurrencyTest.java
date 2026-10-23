
package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class VideoPlayerModelConcurrencyTest {

    // Create the real video model with test callbacks.
    private VideoPlayerModel createModel() {
        return new VideoPlayerModel(
                false,
                List.of(),
                (width, height) -> {},
                image -> {},
                status -> {},
                (playing, paused) -> {},
                enabled -> {}
        );
    }

    // Access the model's existing private frame queue for testing.
    private ArrayBlockingQueue<?> getPreparedFrames(
            VideoPlayerModel model) throws Exception {

        Field field =
                VideoPlayerModel.class.getDeclaredField("preparedFrames");

        field.setAccessible(true);

        return (ArrayBlockingQueue<?>) field.get(model);
    }

    // Access the existing decoder worker field.
    private Field getDecoderWorkerField() throws Exception {
        Field field =
                VideoPlayerModel.class.getDeclaredField("decoderWorker");

        field.setAccessible(true);
        return field;
    }

    // Invoke the model's existing worker shutdown method.
    private void stopDecoderWorker(VideoPlayerModel model)
            throws Exception {

        Method method =
                VideoPlayerModel.class.getDeclaredMethod("stopDecoderWorker");

        method.setAccessible(true);
        method.invoke(model);
    }

    @Test
    void testModelUsesBoundedFrameQueue() throws Exception {
        VideoPlayerModel model = createModel();

        ArrayBlockingQueue<?> queue = getPreparedFrames(model);

        assertNotNull(queue);
        assertEquals(3, queue.size() + queue.remainingCapacity());
    }

    @Test
    void testModelFrameQueueStartsEmpty() throws Exception {
        VideoPlayerModel model = createModel();

        ArrayBlockingQueue<?> queue = getPreparedFrames(model);

        assertTrue(queue.isEmpty());
        assertEquals(3, queue.remainingCapacity());
    }

    @Test
    void testStoppingWithNoWorkerIsSafe() throws Exception {
        VideoPlayerModel model = createModel();

        assertNull(getDecoderWorkerField().get(model));

        assertDoesNotThrow(() -> stopDecoderWorker(model));

        assertNull(getDecoderWorkerField().get(model));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testDecoderWorkerIsInterruptedAndJoined() throws Exception {
        VideoPlayerModel model = createModel();

        CountDownLatch workerStarted = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            workerStarted.countDown();

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "test-video-decoder");

        getDecoderWorkerField().set(model, worker);

        worker.start();

        try {
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));

            stopDecoderWorker(model);

            // The worker must no longer be running.
            assertFalse(worker.isAlive());

            // The model must release its worker reference.
            assertNull(getDecoderWorkerField().get(model));
        } finally {
            worker.interrupt();
            worker.join(2000);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testRepeatedWorkerShutdownIsSafe() throws Exception {
        VideoPlayerModel model = createModel();

        CountDownLatch workerStarted = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            workerStarted.countDown();

            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "test-repeat-decoder");

        getDecoderWorkerField().set(model, worker);

        worker.start();

        try {
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));

            stopDecoderWorker(model);
            stopDecoderWorker(model);
            stopDecoderWorker(model);

            assertFalse(worker.isAlive());
            assertNull(getDecoderWorkerField().get(model));
        } finally {
            worker.interrupt();
            worker.join(2000);
        }
    }
}
