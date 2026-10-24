# Development Log

## 14 October 2026
- Created the GitHub repository using the supplied single-threaded video player
- Reviewed the assignment requirements and existing code structure
- Implemented parsing for all 11 command-line frame effects
- Added support for long options, combined short flags and repeated effects
- Integrated command-line options with the video player model
- Ensured frame effects are applied in the specified order

## 15 October 2026
- Updated the command-line help output to document the available frame effects
- Created 18 JUnit 5 tests for command-line parsing and invalid inputs
- Updated `pom.xml` to include JUnit 5 and Maven Surefire
- Resolved dependency configuration issues preventing test compilation
- Ran the complete test suite successfully with all 18 tests passing

## 20 October 2026
- Reviewed the single-threaded playback pipeline and identified opportunities for concurrency
- Introduced a bounded thread-safe frame queue using `ArrayBlockingQueue`
- Updated frame preparation, display, seeking and cleanup to use the queue
- Tested video playback with multiple frame effects and confirmed it worked
- Implemented a background worker for video decoding and frame processing
- Added thread coordination for seeking, shutdown and audio handling
- Resolved a Maven clean issue caused by file locking by running tests without the clean phase
- Recompiled the application and confirmed all 18 JUnit tests passed
- Tested background playback and observed improved frame-headroom readings
- Continued checking playback controls before committing the concurrent implementation
- Improved coordination between the background decoder and JavaFX rendering thread
- Updated frame buffering to reduce unnecessary polling and improve worker efficiency
- Prevented buffered frames from advancing during paused playback
- Tested playback, pausing, resuming and replaying with multiple effects
- Confirmed all 18 JUnit tests passed and the application ran successfully

## 21 October 2026
- Tested pause, resume and seeking with the background decoder
- Identified an issue where seeking while paused did not update the displayed frame
- Updated playback logic to display the new frame without resuming playback
- Verified that seeking while paused now works correctly
- Reviewed background decoder shutdown and resource cleanup
- Improved thread shutdown coordination and audio resource handling
- Tested stopping, restarting, seeking and closing the player using a 30-second video
- Confirmed playback remained responsive without noticeable freezing
- Reviewed application startup, GUI controls and error-handling behaviour.
- Stress-tested rapid seeking, pausing, restarting and playback completion.
- Confirmed the application handles nonexistent video files with a clear error message.
- Updated command-line help descriptions to match the assignment specification.
- Ran all 18 existing automated tests successfully.

## 23 October 2026 
- Added eight producer-consumer concurrency tests covering bounded queue capacity, FIFO ordering, thread interruption and concurrent load.
- Added five tests targeting the video model's frame buffer and decoder shutdown behaviour.
- Verified that the decoder worker can be interrupted and joined safely in controlled tests.
- Confirmed all 31 automated tests pass with no failures or errors.

## 24 October 2026
- Ran TITAN Part 1 and Part 2 tests to evaluate the concurrent video player against the supplied reference implementation.
- Investigated a failed parallel-processing submission in which some frames were not displayed, and revised the frame-processing pipeline to improve reliability.
- Introduced a direct processing path for lightweight workloads, avoiding unnecessary worker scheduling and frame cloning when only frame numbering was enabled.
- Updated the concurrent pipeline to use a dedicated decoder thread, a bounded queue of processing tasks and a fixed thread pool, while preserving frame display order.
- Tested an initial two-worker configuration, achieving 164.94% performance on TITAN with no reported missed frames.
- Increased the worker count from two to four and expanded the processing buffer, improving TITAN performance to 267.05%.
- Investigated processor-level synchronisation in `FrameMottler` and `FrameBleeder` as a potential bottleneck.
- Experimented with thread-local working buffers and removal of processor locks. This achieved 270.20% performance but reduced startup performance and resulted in more missed frames during testing.
- Reverted the experimental thread-local changes and retained the more reliable synchronised implementation.
- Increased the processing worker count from four to six and the processing buffer capacity from eight to twelve.
- Achieved approximately 373% performance on TITAN Part 2, with CPU utilisation equivalent to approximately 6.75 of the eight available cores.
- Used Maven tests and local JavaFX playback tests to check compilation, concurrency behaviour and playback under multiple frame effects.
- Repeatedly verified that all 31 automated tests passed with no failures or errors.
- Used Git branches, commits and stashing to preserve successful implementations while experimenting with alternative concurrency designs.
- Selected the six-worker implementation for final verification and submission, subject to the final frame-correctness and assessment checks.