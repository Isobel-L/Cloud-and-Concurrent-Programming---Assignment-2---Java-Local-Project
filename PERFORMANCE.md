# Video Player Performance Comparison

## Overview
The aim of this test was to compare the performance of the original single threaded video player with the new concurrent version. I wanted to see whether moving video decoding and frame processing to a separate thread would help the player prepare frames more efficiently and keep playback running smoothly. The main focus was on frame headroom, which tells us how much time is available between preparing a frame and when it is supposed to appear on screen.

## Testing Setup
I tested both versions using the same 30-second, 720p video (`sample-30s-720p.mp4`), without applying any additional frame effects. The video runs at approximately 30 frames per second, meaning there is around 33,333 microseconds (33.3 milliseconds) available for each frame. For the comparison, I used:
- **Original version:** Git commit `9a30914`
- **Concurrent version:** Git commit `3a2ec6c`
Both versions were run using Maven, and I recorded the frame headroom values printed in the terminal.

## Results
Both versions were tested using the same video, with approximately 33.3 milliseconds available per frame.
**Original Single-Threaded Player**
- Typical frame headroom: 17–28 ms
- Negative headroom occurred at startup and a few additional times during playback.
**Concurrent Player**
- Typical frame headroom: 87–97 ms
- Negative headroom occurred at startup, but no further negative values were observed in the recorded output.

The concurrent player showed noticeably more frame headroom than the original version. This means frames were generally being prepared further ahead of their scheduled display times, giving the application more flexibility when displaying them. The difference between the two versions was quite noticeable. In the original player, most frames had around 17–28 milliseconds of headroom. This meant the player was generally keeping up with playback, but frames were being prepared relatively close to when they needed to be displayed. In comparison, the concurrent player regularly showed around 87–97 milliseconds of headroom. This suggests that the background thread was preparing frames well ahead of time, giving the application more breathing room before each frame needed to appear. Both versions had a negative headroom reading when playback first started. However, the original player also recorded a few negative values later in the test, while the concurrent player did not show any further negative values in the recorded output.

## Why the Concurrent Version Performed Differently
The main difference between the two implementations is how they handle video decoding and frame processing.
In the original version, these tasks were carried out on the JavaFX application thread. This meant the same thread responsible for updating the interface and responding to user interactions was also responsible for preparing video frames. In the concurrent version, I moved decoding and frame processing to a background worker thread. The prepared frames are then stored in a thread-safe queue that can hold up to three frames. This allows the background thread to prepare upcoming frames while the JavaFX thread focuses on displaying them and handling user interactions. Based on the results, this approach appears to give the player more time to prepare frames and reduces the risk of decoding work delaying the interface.

## Conclusion
Overall, the initial results suggest that the concurrent implementation is better at preparing frames ahead of their display deadlines.
The original single-threaded player was generally able to keep up with playback, but the concurrent version had noticeably more frame headroom throughout the recorded test. I think the biggest improvement from this approach is that the application no longer relies on the JavaFX thread to handle video decoding and processing. Separating these responsibilities should make the application more responsive, particularly when more demanding frame effects are applied. Although further testing would help confirm the overall performance improvement, the results so far support the decision to use a background decoder thread and a bounded frame buffer.

## Additional Performance Testing — 24 October 2026
After implementing the background decoder, I improved performance further by introducing parallel frame processing. The updated implementation uses a dedicated decoder thread, multiple processing workers and bounded queues to process frames concurrently while preserving their display order.

### TITAN Performance Results
I tested several configurations using TITAN Part 2:

- **2 workers:** 164.94% performance, using 3.78 of 8 CPU cores.
- **4 workers:** 267.05% performance, using 5.33 of 8 CPU cores.
- **4 workers with thread-local effects:** 270.20% performance, using 5.36 of 8 CPU cores.
- **6 workers:** Approximately 373% performance, using 6.75 of 8 CPU cores.

### Debugging and Optimisation
I investigated whether synchronisation in `FrameMottler` and `FrameBleeder` was limiting performance. I experimented with thread-local working buffers to allow these effects to run concurrently without locks. Although this slightly improved performance, it reduced startup speed and resulted in more missed frames during testing. I reverted these changes and retained the original synchronisation.
Increasing the processing worker count from two to six produced the most significant improvement. The final implementation uses six processing workers, a processing queue capacity of twelve frames and a prepared-frame buffer capacity of three frames.

### Conclusion
The results demonstrate that parallel frame processing significantly improves performance compared with the reference implementation. My final version achieved approximately **373% performance** on TITAN Part 2. This testing also highlighted the importance of balancing processing speed with thread safety, frame accuracy and playback responsiveness. The final submission still requires verification of frame correctness across the tested workloads.