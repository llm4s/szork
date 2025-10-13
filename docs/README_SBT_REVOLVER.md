# Szork Server with sbt-revolver

This document describes how to use sbt-revolver for hot reloading the Szork server during development.

## What is sbt-revolver?

sbt-revolver is an SBT plugin that allows you to quickly restart your application in the background while developing. It's particularly useful for web servers like Szork as it provides automatic recompilation and restart on code changes.

## Available Commands

The following custom sbt commands have been configured for the Szork server:

### Basic Commands

- `sbt szorkStart` - Start the Szork server in the background
- `sbt szorkStop` - Stop the running Szork server
- `sbt szorkRestart` - Restart the Szork server (stop and start)
- `sbt szorkStatus` - Check if the Szork server is running

### Triggered Restart Mode (Watch Mode)

- `sbt "~szorkStart"` - Start the server in watch mode. The server will automatically restart whenever you save changes to Scala files.

**Important**: You must use quotes around `~szorkStart` to prevent the shell from interpreting the `~` character. Without quotes, the command won't work properly.

This is the recommended way to develop the Szork server as it provides automatic recompilation and restart on file changes.

## Usage Examples

### Start Development with Auto-Restart

```bash
# Start the server in watch mode - it will restart automatically on file changes
sbt "~szorkStart"
```

### Manual Control

```bash
# Start the server
sbt szorkStart

# Check if it's running
sbt szorkStatus

# Make some code changes, then restart
sbt szorkRestart

# Stop the server when done
sbt szorkStop
```

### Direct sbt-revolver Commands

You can also use the standard sbt-revolver commands directly:

```bash
# Start with revolver
sbt szork/reStart

# Stop with revolver
sbt szork/reStop

# Check status
sbt szork/reStatus

# Watch mode (triggered restart)
sbt ~szork/reStart
```

## Configuration

The sbt-revolver configuration for Szork includes:

- Main class: `org.llm4s.szork.SzorkServer`
- JVM options: `-Xmx1g -XX:MaxMetaspaceSize=512m`
- Port: 8080
- Host: 0.0.0.0
- Watch sources: All Scala files in szork/src, src/main/scala, and shared/src/main/scala

## Troubleshooting

If the server fails to start:

1. Check if port 8080 is already in use
2. Ensure all dependencies are resolved: `sbt szork/compile`
3. Check the logs for compilation errors
4. Try stopping and starting again: `sbt szorkStop` then `sbt szorkStart`

## Development Workflow

1. Open a terminal and run `sbt "~szorkStart"`
2. The server will start on http://localhost:8080
3. Make changes to any Scala files
4. Save the file - the server will automatically recompile and restart
5. Test your changes immediately
6. Press Ctrl+C to stop the watch mode

This provides a smooth development experience with minimal manual intervention.