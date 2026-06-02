#!/bin/bash
# Script to disable unnecessary macOS daemons for a headless iOS build server.

echo "Optimizing Mac Mini for iOS Builds..."

# Function to safely disable and bootout a system service
disable_service() {
    local service=$1
    echo "Disabling $service..."
    
    # Try bootout first (might fail if not currently running, that's fine)
    sudo launchctl bootout system/"$service" 2>/dev/null
    sudo launchctl bootout gui/$(id -u)/"$service" 2>/dev/null
    
    # Disable it from starting automatically
    sudo launchctl disable system/"$service" 2>/dev/null
    sudo launchctl disable gui/$(id -u)/"$service" 2>/dev/null
}

# 1. Machine Learning & Siri Daemons (Known to cause CPU spikes/panics)
disable_service "com.apple.knowledgeconstructiond"
disable_service "com.apple.Siri.agent"
disable_service "com.apple.siriknowledged"
disable_service "com.apple.mediaanalysisd"
disable_service "com.apple.photoanalysisd"

# 2. Consumer/Unneeded Services
disable_service "com.apple.touristd"
disable_service "com.apple.GameController.gamecontrollerd"
disable_service "com.apple.helpd"
disable_service "com.apple.tv.mediaprovidersd"
disable_service "com.apple.gamecenter.gamed"

# 3. Disable Sleep and Screensaver (Standard for headless build servers)
echo "Configuring power management for headless build server..."
sudo pmset -a sleep 0
sudo pmset -a displaysleep 0
sudo pmset -a harddisksleep 0
sudo pmset -a disksleep 0
sudo pmset -a womp 1
sudo pmset -a networkoversleep 0

echo "Optimization complete."
echo "Note: Due to macOS System Integrity Protection (SIP), some core Apple services might log an error when attempting to disable them."
