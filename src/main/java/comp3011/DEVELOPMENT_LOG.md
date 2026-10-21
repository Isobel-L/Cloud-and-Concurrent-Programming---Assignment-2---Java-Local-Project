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