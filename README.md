# COMP3011 Assignment 2 - Concurrent Video Player

## Overview
This project builds on the original single-threaded video player provided for COMP3011 Assignment 2. I extended the application to support command-line frame effects and introduced concurrent video processing to improve playback performance. The updated implementation separates video decoding, frame processing and display across multiple threads, allowing the JavaFX application thread to focus on rendering frames and responding to user interactions.

## Running the Application
The project requires Java 25 and Apache Maven. JavaFX and JavaCV dependencies are managed through Maven.

To run the video player: mvn javafx:run '-Djavafx.args=sample-5s.mp4'
To enable audio and apply multiple frame effects:mvn javafx:run '-Djavafx.args=-a -sfn sample-5s.mp4'
To display the available command-line options: mvn javafx:run '-Djavafx.args=--help'
The application supports 11 frame processors. Effects can be combined, repeated and applied in the order specified on the command line.

## Concurrent Implementation
The original video player performed decoding and frame processing on the JavaFX application thread, which could affect responsiveness when multiple effects were applied.
My final implementation uses a dedicated background thread named `video-decoder` to decode video frames and a fixed thread pool of **six processing workers** to apply frame effects concurrently. Decoded frames are copied before being submitted for processing, preventing the decoder from overwriting image data being used by another thread. Processing tasks are buffered in a bounded queue with a capacity of twelve frames. Completed frames are collected in their original decoding order and placed into a separate `ArrayBlockingQueue` with a capacity of three frames.The JavaFX application thread retrieves prepared frames when they are due to be displayed. For lightweight workloads with no effects or only frame numbering, the player uses a direct processing path to avoid unnecessary thread-pool overhead.

To coordinate concurrency safely, I used:
- Bounded queues to limit buffering and control outstanding processing tasks.
- `volatile` variables to share playback state between threads.
- Synchronisation around frame effects that maintain shared mutable state.
- An `audioLock` to coordinate audio operations.
- Thread interruption, `join()` and executor shutdown to stop background processing before seeking or closing resources.
- Queue notifications to coordinate frame preparation, playback and resumption.

These mechanisms help maintain frame ordering, protect shared resources and keep playback responsive.

## Testing
The project includes 31 automated JUnit tests covering command-line parsing, frame buffering, producer-consumer behaviour and decoder worker shutdown.
To run the tests: mvn test
To run the full Maven verification process: mvn verify

Playback controls, seeking and application responsiveness were also tested manually using a 30-second video with multiple frame effects.

TITAN Part 2 was used to assess performance against the reference player. The six-worker implementation achieved approximately **373% relative performance**. Frame delivery and visual correctness were also evaluated during testing.

## Additional Documentation
- [DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md) - Development progress, debugging and testing.
- [PERFORMANCE.md](PERFORMANCE.md) - Performance comparison and TITAN benchmark results.
- [AI_ACKNOWLEDGEMENT.md](AI_ACKNOWLEDGEMENT.md) - Documentation of AI assistance used throughout the assignment.

## Attribution
The original video player was provided by Simon Ratcliffe for Adelaide University COMP3011 Assignment 2.