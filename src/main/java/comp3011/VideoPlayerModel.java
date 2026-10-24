/**
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing using dedicated decoder and processing worker threads.
 * It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {

    private static final long NO_SEEK_REQUEST = -1;
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    private static final int FRAME_BUFFER_CAPACITY = 3;
    private static final int PROCESSING_BUFFER_CAPACITY = 4;
    private static final int PROCESSING_WORKERS = 2;

    private final List<FrameProcessor> frameProcessors;
    private final Queue<PendingAudio> pendingAudio =
            new ConcurrentLinkedQueue<>();

    private final Object audioLock = new Object();

    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile;
    private FFmpegFrameGrabber grabber;

    // Each processing worker owns its converter;
    // JavaFXFrameConverter is not shared between threads.
    private final ThreadLocal<JavaFXFrameConverter> workerConverters =
            ThreadLocal.withInitial(JavaFXFrameConverter::new);

    private AudioPlayer audioPlayer;

    private final ArrayBlockingQueue<Future<PreparedFrame>> processingFrames =
            new ArrayBlockingQueue<>(PROCESSING_BUFFER_CAPACITY);

    private ExecutorService processingPool;

    private final ArrayBlockingQueue<PreparedFrame> preparedFrames =
            new ArrayBlockingQueue<>(FRAME_BUFFER_CAPACITY);

    private volatile boolean playbackOpen;
    private volatile boolean pauseRequested;
    private volatile boolean audioOutputEnabled;

    private boolean audioAvailable;
    private volatile boolean frameProcessorsInitialised;
    private boolean pausedSeekPreviewPending;

    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;

    private volatile long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private volatile long playbackStartNs;
    private volatile long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    private Thread decoderWorker;
    private volatile boolean decoderFinished;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<String> frameEffectOptions,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {

        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;

        this.frameProcessors = new ArrayList<>();

        // Create one processor per option so duplicates are applied
        // separately and in order.
        for (String option : frameEffectOptions) {
            frameProcessors.add(createFrameProcessor(option));
        }

        // frameProcessors.add(new FrameBleeder());
        // frameProcessors.add(new FrameScratcher());
        // frameProcessors.add(new FrameDuster());
        // frameProcessors.add(new FramePepperer());
        // frameProcessors.add(new FrameBlackAndWhiter());
        // frameProcessors.add(new FrameYellower());
        // frameProcessors.add(new FrameVignetter());
        // frameProcessors.add(new FrameFlickerer());
        // frameProcessors.add(new FrameJitterer());
        // frameProcessors.add(new FrameNumberer());
    }

    // Maps the command-line identifiers to their corresponding processors.
    // Add new processor types here when extending the supported options.
    private FrameProcessor createFrameProcessor(String option) {
        return switch (option) {
            case "-n" -> new FrameNumberer();
            case "-s" -> new FrameScratcher();
            case "-f" -> new FrameFlickerer();
            case "-w" -> new FrameBlackAndWhiter();
            case "-y" -> new FrameYellower();
            case "-v" -> new FrameVignetter();
            case "-d" -> new FrameDuster();
            case "-j" -> new FrameJitterer();
            case "-m" -> new FrameMottler();
            case "-b" -> new FrameBleeder();
            case "-p" -> new FramePepperer();
            default -> throw new IllegalArgumentException(
                    "Unknown frame processor option: " + option);
        };
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }
        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(
                        displayableSeekTimestamp(currentTimestampUs),
                        false,
                        currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
            pausedSeekPreviewPending = false;

            synchronized (preparedFrames) {
                preparedFrames.notifyAll();
            }
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        synchronized (audioLock) {
            audioOutputEnabled = !audioOutputEnabled;
            pendingAudio.clear();
            flushAudioOutput();
        }

        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;

        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(
                    grabTimestampUs,
                    pauseRequested,
                    logicalTimestampUs);
            return;
        }

        try {
            stopDecoderWorker();
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            startDecoderWorker();
            notifyPlaybackStateChanged();

        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(
            long startTimestampUs,
            boolean initiallyPaused,
            long initialRelativeSeekBaseUs) {

        closePlaybackResources();

        pauseRequested = initiallyPaused;

        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;

        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);

            playbackOpen = true;
            playbackTimer.start();
            startDecoderWorker();

            notifyPlaybackStateChanged();

        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs)
            throws Exception {

        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(
                grabber.getImageWidth(),
                grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);

        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;

        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();

        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();

        if (audioAvailable) {
            audioPlayer.open(
                    grabber.getSampleRate(),
                    grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        preparedFrames.clear();
        processingFrames.clear();

        decoderFinished = false;
        pausedSeekPreviewPending = pauseRequested;

        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }

        pauseStartedNs = 0;
    }

    // Frame numbering is inexpensive and deterministic.
    // Bypassing the pool avoids costly per-frame copies and worker scheduling
    // during playback with no effects or only frame numbering.
    private boolean usesDirectFramePipeline() {
        return frameProcessors.stream().allMatch(
                processor -> processor instanceof FrameNumberer);
    }

    private void startDecoderWorker() {
        decoderFinished = false;

        // Avoid starting a processing pool when no heavy effects are selected.
        if (!usesDirectFramePipeline()) {
            processingPool = Executors.newFixedThreadPool(
                    PROCESSING_WORKERS,
                    task -> {
                        Thread thread = new Thread(
                                task,
                                "frame-processor");

                        thread.setDaemon(true);
                        return thread;
                    });
        }

        decoderWorker = new Thread(
                this::runDecoderWorker,
                "video-decoder");

        decoderWorker.setDaemon(true);
        decoderWorker.start();
    }

    private void runDecoderWorker() {
        Thread currentWorker = Thread.currentThread();

        try {
            while (playbackOpen && !currentWorker.isInterrupted()) {

                if ((usesDirectFramePipeline()
                        ? preparedFrames.remainingCapacity() == 0
                        : processingFrames.remainingCapacity() == 0)
                        || (pauseRequested
                        && (!processingFrames.isEmpty()
                        || !preparedFrames.isEmpty()))) {

                    synchronized (preparedFrames) {
                        preparedFrames.wait(25);
                    }

                    continue;
                }

                if (usesDirectFramePipeline()) {
                    prepareUnprocessedFrame();
                } else {
                    prepareNextFrame();
                }

                if (decoderFinished) {
                    return;
                }
            }

        } catch (InterruptedException e) {
            currentWorker.interrupt();

        } catch (Exception e) {
            Platform.runLater(() -> {
                if (playbackOpen && decoderWorker == currentWorker) {
                    handlePlaybackError(e);
                }
            });
        }
    }

    private void stopDecoderWorker() {
        Thread worker = decoderWorker;

        if (worker != null) {
            worker.interrupt();

            synchronized (preparedFrames) {
                preparedFrames.notifyAll();
            }

            if (worker != Thread.currentThread()) {
                boolean interrupted = false;

                while (worker.isAlive()) {
                    try {
                        worker.join();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }

                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            decoderWorker = null;
        }

        // Cancel outstanding work before releasing decoding resources.
        for (Future<PreparedFrame> future : processingFrames) {
            future.cancel(true);
        }

        processingFrames.clear();

        ExecutorService pool = processingPool;
        processingPool = null;

        if (pool != null) {
            pool.shutdownNow();

            boolean interrupted = false;

            while (true) {
                try {
                    if (pool.awaitTermination(
                            100,
                            TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Playback with no effects or only frame numbering uses a direct
    // decoder-to-display pipeline, avoiding extra copies and futures.
    private void prepareUnprocessedFrame() throws Exception {
        if (!playbackOpen
                || preparedFrames.remainingCapacity() == 0) {
            return;
        }

        while (playbackOpen
                && !Thread.currentThread().isInterrupted()) {

            Frame frame = grabFrame();

            if (frame == null) {
                decoderFinished = true;
                return;
            }

            long timestampUs = grabber.getTimestamp();

            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();

                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }

            if (!frameProcessorsInitialised) {
                initialiseFrameProcessors(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()));

                frameProcessorsInitialised = true;
            }

            int frameNumber = grabber.getFrameNumber();

            if (!frameProcessors.isEmpty()) {
                processFrame(
                        frame,
                        new InfoFrame(frameNumber, timestampUs));
            }

            Image image = workerConverters.get().convert(frame);

            long relativeTimestampUs = Math.max(
                    0,
                    timestampUs - firstTimestampUs);

            long logicalTimestampUs =
                    logicalPlaybackBaseUs + relativeTimestampUs;

            long targetTimeNs =
                    playbackStartNs + relativeTimestampUs * 1_000L;

            preparedFrames.put(new PreparedFrame(
                    image,
                    frameNumber,
                    timestampUs,
                    logicalTimestampUs,
                    targetTimeNs,
                    System.nanoTime()));

            return;
        }
    }

    private void prepareNextFrame() throws Exception {
        if (!playbackOpen
                || processingFrames.remainingCapacity() == 0) {
            return;
        }

        DecodedFrame decoded = readNextVideoFrame();

        if (decoded == null || !playbackOpen) {
            return;
        }

        // The bounded queue provides backpressure.
        // Futures are stored in decoding order.
        Future<PreparedFrame> future = processingPool.submit(
                () -> processDecodedFrame(decoded));

        processingFrames.put(future);
    }

    private DecodedFrame readNextVideoFrame() throws Exception {
        while (playbackOpen
                && !Thread.currentThread().isInterrupted()) {

            Frame frame = grabFrame();

            if (frame == null) {
                decoderFinished = true;
                return null;
            }

            long timestampUs = grabber.getTimestamp();

            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (!frameProcessorsInitialised) {
                initialiseFrameProcessors(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()));

                frameProcessorsInitialised = true;
            }

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();

                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }

            int frameNumber = grabber.getFrameNumber();

            long relativeTimestampUs = Math.max(
                    0,
                    timestampUs - firstTimestampUs);

            long logicalTimestampUs =
                    logicalPlaybackBaseUs + relativeTimestampUs;

            long targetTimeNs =
                    playbackStartNs + relativeTimestampUs * 1_000L;

            // Clone before JavaCV reuses the decoder's frame buffer.
            return new DecodedFrame(
                    frame.clone(),
                    frameNumber,
                    timestampUs,
                    logicalTimestampUs,
                    targetTimeNs);
        }

        return null;
    }

    private PreparedFrame processDecodedFrame(DecodedFrame decoded)
            throws Exception {

        Frame frame = decoded.frame();

        processFrame(
                frame,
                new InfoFrame(
                        decoded.frameNumber(),
                        decoded.mediaTimestampUs()));

        Image image = workerConverters.get().convert(frame);

        return new PreparedFrame(
                image,
                decoded.frameNumber(),
                decoded.mediaTimestampUs(),
                decoded.logicalTimestampUs(),
                decoded.targetTimeNs(),
                System.nanoTime());
    }

    // Move completed frames into the display queue in decoding order,
    // not in the order in which worker threads finish.
    private void collectProcessedFrames() {
        while (preparedFrames.remainingCapacity() > 0) {

            Future<PreparedFrame> head = processingFrames.peek();

            if (head == null || !head.isDone()) {
                break;
            }

            processingFrames.poll();

            try {
                preparedFrames.add(head.get());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;

            } catch (ExecutionException e) {
                Throwable cause = e.getCause();

                handlePlaybackError(
                        cause instanceof Exception
                                ? (Exception) cause
                                : new Exception(cause));
                return;

            } catch (java.util.concurrent.CancellationException e) {
                return;
            }

            synchronized (preparedFrames) {
                preparedFrames.notifyAll();
            }
        }
    }

    // Called regularly on the JavaFX application thread.
    // Keeps audio flowing and displays prepared frames when due.
    // Decoding happens on a background thread. For heavier effects,
    // processing occurs in a worker pool and the frames are reordered
    // before display.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        // Task (1): Schedule audio.
        writeDueAudio(now);

        // Collect any completed processed frames.
        collectProcessedFrames();

        // Task (2): Display frames on time.
        PreparedFrame nextFrame = preparedFrames.peek();

        if (nextFrame != null
                && firstTimestampUs != NO_SEEK_REQUEST) {

            if (pauseRequested && pausedSeekPreviewPending) {

                // Display the sought frame once without resuming playback.
                pausedSeekPreviewPending = false;
                displayPreparedFrame(now);

            } else if (!pauseRequested) {

                long relativeTimestampUs = Math.max(
                        0,
                        nextFrame.mediaTimestampUs() - firstTimestampUs);

                long dueTimeNs =
                        playbackStartNs + relativeTimestampUs * 1_000L;

                if (dueTimeNs <= now) {

                    // Display the next frame when its time arrives.
                    displayPreparedFrame(now);
                }
            }
        }

        // Finish once decoding, processing, display and audio are complete.
        if (!pauseRequested
                && decoderFinished
                && processingFrames.isEmpty()
                && preparedFrames.isEmpty()
                && pendingAudio.isEmpty()) {

            finishPlayback();
        }
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrames.poll();

        if (frame == null) {
            return;
        }

        synchronized (preparedFrames) {
            preparedFrames.notifyAll();
        }

        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Print frame headroom approximately once per second.
        if (intFrameRate > 0
                && frame.frameNumber() % intFrameRate == 0) {

            long relativeTimestampUs = Math.max(
                    0,
                    frame.mediaTimestampUs() - firstTimestampUs);

            long dueTimeNs =
                    playbackStartNs + relativeTimestampUs * 1_000L;

            long remainingUs =
                    (dueTimeNs - frame.preparedAtNs()) / 1_000L;

            if (remainingUs < 0) {
                System.out.printf(
                        "Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf(
                        "Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        synchronized (audioLock) {
            if (!audioOutputEnabled || audioPlayer == null) {
                return;
            }

            byte[] samples = audioPlayer.copySamples(frame);

            if (samples.length > 0) {
                pendingAudio.add(
                        new PendingAudio(timestampUs, samples));
            }
        }
    }

    private void writeDueAudio(long now) {
        synchronized (audioLock) {

            // Handle early exits.
            if (!audioOutputEnabled || audioPlayer == null) {
                pendingAudio.clear();
                return;
            }

            if (pauseRequested
                    || firstTimestampUs == NO_SEEK_REQUEST
                    || playbackStartNs <= 0) {
                return;
            }

            long dueTimestampUs = firstTimestampUs
                    + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;

            while (!pendingAudio.isEmpty()) {
                PendingAudio audio = pendingAudio.peek();

                if (audio.timestampUs() > dueTimestampUs) {
                    return;
                }

                int written = audioPlayer.write(
                        audio.samples(),
                        audio.offset(),
                        audio.remaining());

                if (written <= 0) {
                    return;
                }

                audio.advance(written);

                if (!audio.finished()) {
                    return;
                }

                pendingAudio.remove();
            }
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();

        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;

        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();

        closePlaybackResources();

        pauseRequested = false;

        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;

        stopDecoderWorker();

        preparedFrames.clear();
        processingFrames.clear();

        synchronized (audioLock) {
            pendingAudio.clear();
        }

        decoderFinished = false;
        pausedSeekPreviewPending = false;
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            synchronized (audioLock) {
                try {
                    audioPlayer.close();
                } catch (Exception e) {
                    // Continue releasing remaining playback resources.
                } finally {
                    audioPlayer = null;
                }
            }
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // Continue releasing remaining playback resources.
            }

            try {
                grabber.close();
            } catch (Exception e) {
                // Continue releasing remaining playback resources.
            }

            grabber = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');

        if (extensionStart <= 0) {
            return fileName;
        }

        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;

        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(
                    clampedTimestampUs,
                    durationUs);
        }

        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;

        if (durationUs == NO_SEEK_REQUEST
                || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;

        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void processFrame(Frame frame, InfoFrame info)
            throws Exception {

        for (FrameProcessor processor : frameProcessors) {

            // These effects reuse mutable per-instance buffers or caches.
            if (processor instanceof FrameMottler
                    || processor instanceof FrameBleeder) {

                synchronized (processor) {
                    processor.process(frame, info);
                }

            } else {
                processor.process(frame, info);
            }
        }
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo)
            throws Exception {

        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }

        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        synchronized (audioLock) {
            if (audioPlayer != null) {
                audioPlayer.flush();
            }
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(
                playbackOpen,
                pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(
                audioOutputEnabled);
    }

    private record DecodedFrame(
            Frame frame,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs) {
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
