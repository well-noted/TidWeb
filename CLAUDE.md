# TidWeb Development Guidelines

## Build Commands
- Build debug app: `./gradlew assembleDebug`
- Build release app: `./gradlew assembleRelease`
- Install debug app: `./gradlew installDebug`
- Run lint: `./gradlew lint`
- Run all tests: `./gradlew test`
- Run a single test: `./gradlew test --tests "com.tiddlywikibrowser.MainActivityTest.helloWorld"`

## Code Style Guidelines
- **Kotlin**: Follow Kotlin style guide with 4-space indentation
- **Imports**: Group by package, no wildcards, alphabetically sorted
- **Naming**: camelCase for variables/functions, PascalCase for classes
- **Types**: Use explicit types for public APIs, infer for local variables
- **Error Handling**: Use sealed Result classes or exception handling with meaningful messages
- **Compose UI**: Use Material3 theme consistently, extract reusable composables
- **Comments**: Document all public interfaces, focus on "why" not "what"
- **Testing**: Add unit tests for logic components, UI tests for critical flows

## Architecture
- Follow MVVM pattern with Compose UI
- Use coroutines for async operations 
- Prefer immutable data, state hoisting for UI components

## Changes Made in Single-File Branch

### 1. Media Playback Improvements
We've enhanced the ExoPlayerManager.kt file with:
- Better HTTP data source factory with improved timeout and retry parameters
- Enhanced media item creation with robust URL handling for different formats
- Improved handling of media metadata for better notification display
- More reliable playback state management

### 2. UI Navigation Hiding During Scroll
We've implemented:
- A new WikiViewComposable.kt file that injects JavaScript for scroll detection
- JavaScript to detect scroll direction and show/hide navigation bars accordingly
- Throttled event handling to prevent excessive UI updates
- Auto-restoration of UI after scrolling stops

### 3. Sharing Functionality Enhancement
These improvements need to be manually added to MainActivity.kt:
- Improved tiddler content extraction with proper tag support
- Better formatting of shared content for readability
- Add a formatTagsForSharing helper function
- More reliable title extraction using data-tiddler-title attribute

## Manual Updates Needed
For the sharing functionality, the changes need to be manually applied to MainActivity.kt:

1. Change the JavaScript in the Share Current Tiddler action to extract tags and title more carefully
2. Update the Intent creation to include EXTRA_SUBJECT and format the shared text with tags
3. Add a utility function for formatting tags from JSONArray

## Future Work
- Consider adding support for sharing images from tiddlers
- Improve scroll detection with finer-grained control
- Add support for background audio playback