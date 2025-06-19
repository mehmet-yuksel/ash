# Ash - Scala 3 with Mill

A Scala 3 project using Mill build tool.

## Building and Running

Make sure you have Mill installed. If not, you can install it using:

```bash
# On Windows with Scoop
scoop install mill

# Or download from https://mill-build.com/mill/Installation.html
```

### Available Commands

- `mill ash.compile` - Compile the project
- `mill ash.run` - Run the main application
- `mill ash.test` - Run tests
- `mill ash.console` - Start Scala REPL with project classpath
- `mill clean` - Clean build artifacts

## Dependencies

- Scala 3.3.1
- utest 0.8.5
