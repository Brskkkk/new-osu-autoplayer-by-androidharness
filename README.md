# osu! Autoplayer

A standalone Android osu!mania autoplayer built from scratch using pure Kotlin and native Android APIs.

## IMPORTANT: Supported Game Mode

**This application supports osu!mania mode only.**

osu!mania features:
- Vertical falling notes
- Multiple lanes (typically 4K, 7K)
- Chords (simultaneous notes across lanes)
- Hold notes (notes that must be held for a duration)

osu!standard (cursor movement, circles, sliders, spinners) is NOT supported.

---

## Core Architecture

```
autoplayer_osu/
├── capture/           # Screen capture using MediaProjection (format-querying, stride handling)
├── detection/         # Note detection (HSV color segmentation, shape analysis, lane restriction)
├── tracking/          # Note tracking (alpha-beta filter, direct tail tracking, velocity from observations)
├── input/             # Input scheduling (dispatchGesture(), GestureDescription, gesture coordinator)
├── calibration/       # Judgement line detection (Sobel edge detection)
├── settings/          # Preferences and multi-profile management
├── ui/                # User interface (MainActivity, DebugOverlayView)
├── processing/        # Frame processing pipeline (frame acquisition, component integration)
├── diagnostics/       # Performance monitoring
└── utils/             # Error recovery and edge case handling
```

---

## Critical Design Decisions (Honest Limitations)

### Input System

The application uses **Android AccessibilityService `dispatchGesture()` with `GestureDescription`**, not `ACTION_CLICK`, as the primary input mechanism.

**Important limitations:**
- Android does NOT guarantee that dispatching a new gesture will preserve an already-dispatched gesture. Overlapping dispatches may cancel existing ones.
- There is no synchronous confirmation of gesture completion via the standard API.
- True simultaneous multi-touch cannot be guaranteed.
- Gesture completion/cancellation callbacks are not available through `dispatchGesture()`.

**How the app handles this:**
- A centralized `GestureCoordinator` (in `InputScheduler`) owns all scheduled presses, releases, and active hold paths
- Before scheduling a gesture, the coordinator inspects all active and upcoming inputs
- Compatible simultaneous presses are combined into single multi-stroke gestures where supported
- Gesture state is tracked as "pending" until observed changes occur
- All active gestures are released during shutdown or errors
- If a required input pattern cannot be supported reliably, the limitation is documented

### Screen Capture

The application does NOT assume RGB565 format. It queries `ImageReader` for supported formats and handles pixel stride, row stride, and buffer layout correctly.

**Supported formats attempted (in order):**
1. `RGBA_8888` (most widely supported)
2. `RGB_565`
3. `YUV_420_888`

### Note Detection

Detection uses:
- HSV color segmentation with configurable ranges
- Shape analysis (bounding box, aspect ratio)
- Lane restrictions (osu!mania lanes)
- Temporal consistency filtering
- Skin adaptation support

**Limitations:**
- Fixed HSV ranges may not work for all skins
- Custom color ranges can be configured via SkinAdaptationManager
- Detection resolution scales with capture resolution
- Manual fallback available for low-confidence situations

### Hold Notes

Hold notes are tracked through a direct tail observation system:
- Hold head detected → tracking head
- Hold tail detected → direct tail tracking
- Release estimate updated as new tail observations arrive
- Not calculated once from head-to-tail distance

---

## Installation

1. Build the APK using Android Studio or via command line
2. Install on Android 10 (API 29) or higher device
3. Grant required permissions:
   - **Screen Capture** (MediaProjection)
   - **Accessibility Service** (for touch injection)
   - **Display Overlay** (for floating controls)
   - **Foreground Service** (for sustained processing)

---

## Permissions

| Permission | Purpose | Required |
|------------|---------|----------|
| `MEDIA_PROJECTION` | Capture gameplay screen | Yes |
| `BIND_ACCESSIBILITY_SERVICE` | Inject touch inputs | Yes |
| `SYSTEM_ALERT_WINDOW` | Floating control overlay | Yes |
| `FOREGROUND_SERVICE` | Background processing | Yes |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Media projection as foreground | Yes (Android 10+) |

---

## Configuration

Settings are stored in SharedPreferences and accessible via the Settings screen:

### Capture Settings
- **Capture Resolution**: 640x480 to 1920x1080 (default: 1280x720)
- **Processing Frequency**: 5-60 FPS (default: 30)

### Detection Settings
- **Detection Threshold**: 0-255 (default: 30)
- **Timing Offset**: -50 to +50 ms (default: 0)
- **Note Association Tolerance**: 20-150 px (default: 60)
- **Fast Note Detection**: Enabled by default

### Tracking Settings
- **Velocity Smoothing**: 0.0-1.0 (default: 0.7)
- **Track Expiration**: 500-5000 ms (default: 2000)
- **Max Missed Detections**: 1-10 (default: 3)
- **Arrival Prediction**: Enabled by default

### Hold Note Settings
- **Hold Tail Detection**: Enabled by default
- **Min Hold Duration**: 50-1000 ms (default: 200)

### Input Settings
- **Chord Grouping**: Enabled by default
- **Max Chord Size**: 1-10 (default: 4)
- **Chord Grouping Window**: 5-50 ms (default: 15)

### Display Settings
- **Debug Overlay**: Shows detection boxes and tracking info
- **Performance Logging**: Logs FPS, latency, dropped frames
- **Overlay Opacity**: 0.1-1.0 (default: 0.8)

---

## Multi-Profile Support

The application supports multiple profiles for different layouts, games, or resolutions:

- **Create**: New profile with default values
- **Rename**: Change profile name
- **Duplicate**: Copy an existing profile
- **Reset**: Reset profile to default values
- **Delete**: Remove a profile (cannot delete default)
- **Export/Import**: Save/load profiles as JSON

**Import validation:** Invalid profiles are rejected. Failed automatic calibration never overwrites a known-good profile.

---

## Diagnostics

The diagnostics page displays:
- Capture resolution and frame rate
- Frame-processing rate
- Average and maximum processing time
- Frame age
- Dropped frames
- Active note tracks
- Detection confidence
- Estimated arrival times
- Scheduled input times
- Input-dispatch latency
- Active holds
- Gesture cancellations
- Calibration confidence

---

## Testing

### Unit Tests

Run unit tests with:
```bash
./gradlew test
```

Tests cover:
- Note-to-track association
- Velocity estimation
- Arrival-time prediction
- Track expiration and recovery
- Duplicate prevention
- Chord grouping
- Input scheduling
- Hold-note state transitions
- Profile serialization
- Calibration coordinate transformations

### Manual Testing Checklist

1. Verify all permissions request correctly
2. Confirm screen capture shows gameplay footage
3. Test note detection with various note colors and speeds
4. Validate tracking accuracy with accelerating/decelerating notes
5. Check chord detection with simultaneous notes
6. Test hold note detection and sustained input
7. Verify calibration works across different resolutions
8. Confirm floating controls remain draggable and responsive
9. Test profile save/load functionality
10. Validate performance metrics display correctly

---

## Known Limitations

1. **Input Latency:** AccessibilityService dispatchGesture has inherent latency (typically 5-20ms). Microsecond-accurate timing is NOT promised.

2. **Gesture Cancellation:** Dispatching a new gesture may cancel an existing one. The app mitigates this with a centralized gesture coordinator but cannot eliminate the risk.

3. **Hold Persistence:** Cannot guarantee that hold gestures persist across new gesture dispatches. This is an Android API limitation.

4. **Detection Reliability:** Color-based detection may fail with custom skins, unusual lighting, or significant visual effects.

5. **Resolution Scaling:** Detection parameters scale with resolution but may need manual tuning for extreme cases.

6. **osu!mania Only:** osu!standard mode is NOT supported.

---

## Building

### Android Studio
1. Open the project in Android Studio Flamingo or later
2. Select a device and click Run
3. The app will build, install, and launch

### Command Line
```bash
chmod +x gradlew
./gradlew assembleDebug
```

### GitHub Actions
The repository includes a workflow that builds the APK on pushes and pull requests. Artifacts can be downloaded from the Actions tab.

---

## Privacy

- All processing happens locally on the device
- No gameplay images or videos are uploaded or transmitted
- No personal data is collected or stored
- Settings and profiles are stored locally in private storage

---

## License

MIT License - see LICENSE file for details.
