#!/bin/bash

# Szork Development Server Script
# Starts backend (hot-reload) and frontend (dev mode) with clean shutdown

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
BACKEND_PORT=8090
WEBSOCKET_PORT=9002
FRONTEND_PORT=3090
FRONTEND_URL="http://localhost:${FRONTEND_PORT}"
FRONTEND_DIR="frontend"

# Process tracking
BACKEND_PID=""
FRONTEND_PID=""

# Cleanup function
cleanup() {
    echo -e "\n${YELLOW}Shutting down development servers...${NC}"

    # Kill frontend
    if [ ! -z "$FRONTEND_PID" ]; then
        echo -e "${YELLOW}Stopping frontend (PID: $FRONTEND_PID)...${NC}"
        kill -TERM "$FRONTEND_PID" 2>/dev/null || true
        wait "$FRONTEND_PID" 2>/dev/null || true
    fi

    # Kill backend (sbt can be tricky, kill the whole process group)
    if [ ! -z "$BACKEND_PID" ]; then
        echo -e "${YELLOW}Stopping backend (PID: $BACKEND_PID)...${NC}"
        # Kill the entire process group to catch sbt and its children
        pkill -P "$BACKEND_PID" 2>/dev/null || true
        kill -TERM "$BACKEND_PID" 2>/dev/null || true
        wait "$BACKEND_PID" 2>/dev/null || true
    fi

    # Force kill any remaining processes on our ports
    echo -e "${YELLOW}Cleaning up any remaining processes...${NC}"
    lsof -ti:${BACKEND_PORT} | xargs kill -9 2>/dev/null || true
    lsof -ti:${WEBSOCKET_PORT} | xargs kill -9 2>/dev/null || true
    lsof -ti:${FRONTEND_PORT} | xargs kill -9 2>/dev/null || true

    # Kill any remaining sbt processes related to szork
    pkill -f "sbt.*szork" 2>/dev/null || true

    echo -e "${GREEN}Cleanup complete${NC}"
    exit 0
}

# Trap signals for clean shutdown
trap cleanup SIGINT SIGTERM EXIT

echo -e "${GREEN}=== Szork Development Environment ===${NC}"
echo ""

# Step 1: Kill any existing instances
echo -e "${YELLOW}Killing any existing instances...${NC}"
lsof -ti:${BACKEND_PORT} | xargs kill -9 2>/dev/null || true
lsof -ti:${WEBSOCKET_PORT} | xargs kill -9 2>/dev/null || true
lsof -ti:${FRONTEND_PORT} | xargs kill -9 2>/dev/null || true
pkill -f "sbt.*szork" 2>/dev/null || true
sleep 1
echo -e "${GREEN}✓ Cleaned up old instances${NC}"
echo ""

# Step 2: Start backend with hot reload
echo -e "${YELLOW}Starting backend (hot-reload mode)...${NC}"
sbt "~szorkStart" > backend.log 2>&1 &
BACKEND_PID=$!
echo -e "${GREEN}✓ Backend starting (PID: $BACKEND_PID)${NC}"

# Wait for backend to be ready
echo -e "${YELLOW}Waiting for backend to be ready on port ${BACKEND_PORT}...${NC}"
for i in {1..30}; do
    if lsof -ti:${BACKEND_PORT} > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Backend is ready!${NC}"
        break
    fi
    if ! ps -p $BACKEND_PID > /dev/null 2>&1; then
        echo -e "${RED}✗ Backend failed to start. Check backend.log for errors.${NC}"
        tail -20 backend.log
        exit 1
    fi
    sleep 1
    echo -n "."
done

if ! lsof -ti:${BACKEND_PORT} > /dev/null 2>&1; then
    echo -e "\n${RED}✗ Backend failed to start within 30 seconds${NC}"
    echo -e "${YELLOW}Last 20 lines of backend.log:${NC}"
    tail -20 backend.log
    exit 1
fi
echo ""

# Step 3: Start frontend
echo -e "${YELLOW}Starting frontend (dev mode)...${NC}"
cd "$FRONTEND_DIR"
npm run dev > ../frontend.log 2>&1 &
FRONTEND_PID=$!
cd ..
echo -e "${GREEN}✓ Frontend starting (PID: $FRONTEND_PID)${NC}"

# Wait for frontend to be ready
echo -e "${YELLOW}Waiting for frontend to be ready on port ${FRONTEND_PORT}...${NC}"
for i in {1..30}; do
    if lsof -ti:${FRONTEND_PORT} > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Frontend is ready!${NC}"
        break
    fi
    if ! ps -p $FRONTEND_PID > /dev/null 2>&1; then
        echo -e "${RED}✗ Frontend failed to start. Check frontend.log for errors.${NC}"
        tail -20 frontend.log
        exit 1
    fi
    sleep 1
    echo -n "."
done

if ! lsof -ti:${FRONTEND_PORT} > /dev/null 2>&1; then
    echo -e "\n${RED}✗ Frontend failed to start within 30 seconds${NC}"
    echo -e "${YELLOW}Last 20 lines of frontend.log:${NC}"
    tail -20 frontend.log
    exit 1
fi
echo ""

# Step 4: Open browser
echo -e "${GREEN}Opening browser at ${FRONTEND_URL}${NC}"
sleep 1
if command -v open > /dev/null; then
    # macOS
    open "$FRONTEND_URL"
elif command -v xdg-open > /dev/null; then
    # Linux
    xdg-open "$FRONTEND_URL"
else
    echo -e "${YELLOW}Could not detect browser launcher. Please open ${FRONTEND_URL} manually.${NC}"
fi
echo ""

# Step 5: Monitor processes
echo -e "${GREEN}=== Development servers running ===${NC}"
echo -e "Backend:  http://localhost:${BACKEND_PORT}"
echo -e "Frontend: ${FRONTEND_URL}"
echo -e "WebSocket: ws://localhost:${WEBSOCKET_PORT}"
echo ""
echo -e "${YELLOW}Logs:${NC}"
echo -e "  Backend:  tail -f backend.log"
echo -e "  Frontend: tail -f frontend.log"
echo ""
echo -e "${GREEN}Press Ctrl+C to stop all servers${NC}"
echo ""

# Monitor both processes - exit if either dies
while true; do
    if ! ps -p $BACKEND_PID > /dev/null 2>&1; then
        echo -e "\n${RED}✗ Backend process died unexpectedly${NC}"
        echo -e "${YELLOW}Last 20 lines of backend.log:${NC}"
        tail -20 backend.log
        exit 1
    fi

    if ! ps -p $FRONTEND_PID > /dev/null 2>&1; then
        echo -e "\n${RED}✗ Frontend process died unexpectedly${NC}"
        echo -e "${YELLOW}Last 20 lines of frontend.log:${NC}"
        tail -20 frontend.log
        exit 1
    fi

    sleep 2
done
