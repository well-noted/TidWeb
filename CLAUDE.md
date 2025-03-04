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