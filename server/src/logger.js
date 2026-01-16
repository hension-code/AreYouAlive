const fs = require('fs');
const path = require('path');

const LOG_FILE = path.join(__dirname, '..', 'activity.log');

/**
 * Log an activity event to activity.log
 * @param {string} deviceId 
 * @param {string} userName 
 * @param {string} event - The event that triggered the update (heartbeat, register, etc.)
 * @param {Date} timestamp 
 */
function logActivity(deviceId, userName, event, timestamp = new Date()) {
    const timeStr = timestamp.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
    const logMessage = `[${timeStr}] Device: ${deviceId} | User: ${userName} | Event: ${event}\n`;

    fs.appendFile(LOG_FILE, logMessage, (err) => {
        if (err) {
            console.error('Failed to write to activity.log:', err);
        }
    });
}

module.exports = { logActivity };
