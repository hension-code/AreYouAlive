const nodemailer = require('nodemailer');
const { User } = require('./database');
const { Op } = require('sequelize');
const { logActivity } = require('./logger');

let lastRun = null;
const CHECK_INTERVAL_MS = 60 * 1000;

// Configuration from environment variables
const SYSTEM_EMAIL = process.env.SYSTEM_EMAIL;
const SYSTEM_PASSWORD = process.env.SYSTEM_PASSWORD;

function createTransporter() {
    return nodemailer.createTransport({
        service: 'gmail',
        auth: {
            user: SYSTEM_EMAIL,
            pass: SYSTEM_PASSWORD
        }
    });
}

/**
 * Check for users who have exceeded their timeout
 */
async function checkTimeouts() {
    lastRun = new Date();
    try {
        const now = new Date();
        const users = await User.findAll();

        for (const user of users) {
            const lastActive = new Date(user.lastHeartbeat);
            const timeoutMs = user.timeoutHours * 60 * 60 * 1000;
            const diff = now - lastActive;

            // 1. Check for timeout
            if (diff > timeoutMs) {
                if (!user.isAlerting) {
                    console.log(`User ${user.userName} (${user.deviceId}) is inactive! Sending Alert...`);
                    const success = await sendEmail(user, 'alert', diff);
                    if (success) {
                        user.isAlerting = true;
                        user.lastAlertTime = now;
                        await user.save();
                        logActivity(user.deviceId, user.userName, `Alert Sent (Inactive for ${Math.floor(diff / (1000 * 60 * 60))}h)`);
                    }
                }
            }
            // 2. Check for recovery (if server-side heartbeat didn't already handle it)
            // Note: server.js usually handles this, but we keep it here for robustness
            else if (user.isAlerting) {
                console.log(`User ${user.userName} has recovered. Sending Resolved email...`);
                const success = await sendEmail(user, 'resolved');
                if (success) {
                    user.isAlerting = false;
                    await user.save();
                }
            }
        }
    } catch (error) {
        console.error('Monitoring Error:', error);
    }
}

/**
 * Send Emergency or Recovery Email
 * @param {object} user - User record
 * @param {'alert'|'resolved'} type - Type of email
 * @param {number} [diffMs] - Inactivity duration in ms (for alert)
 */
async function sendEmail(user, type, diffMs) {
    if (!SYSTEM_EMAIL || !SYSTEM_PASSWORD) {
        console.error('Email configurations missing in .env');
        return false;
    }

    const destEmail = user.getDecryptedCredentials().emergencyEmail;
    if (!destEmail) return false;

    const transporter = createTransporter();
    const timeString = new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
    const lastActiveStr = new Date(user.lastHeartbeat).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });

    let subject, text;
    if (type === 'alert') {
        const hoursInactive = Math.floor(diffMs / (1000 * 60 * 60));
        subject = `【紧急求助】${user.userName} 已失联超过 ${hoursInactive} 小时`;
        text = `紧急通知\n========\n\n"活着吗"应用检测到用户长时间未使用手机，可能需要帮助。\n\n【用户姓名】${user.userName}\n【失联时长】${hoursInactive} 小时\n【上次活跃】${lastActiveStr}\n【检测时间】${timeString}\n\n请尽快尝试联系 ${user.userName} 确认其安全状况。\n\n---\n此邮件由 "Are You Alive" 服务端自动发送`;
    } else {
        subject = `【警报解除】${user.userName} 已恢复活跃`;
        text = `警报解除通知\n============\n\n用户 ${user.userName} 已于 ${timeString} 重新建立连接，目前状态为活跃。\n\n【最新活跃】${lastActiveStr}\n\n您可以放心，系统将继续监控。\n\n---\n此邮件由 "Are You Alive" 服务端自动发送`;
    }

    try {
        await transporter.sendMail({
            from: `"Are You Alive App" <${SYSTEM_EMAIL}>`,
            to: destEmail, // Nodemailer supports comma-separated strings natively
            subject: subject,
            text: text
        });
        console.log(`${type === 'alert' ? 'Alert' : 'Resolved'} email sent to ${destEmail}`);
        return true;
    } catch (error) {
        console.error(`Failed to send ${type} email to ${destEmail}:`, error);
        logActivity(user.deviceId, user.userName, `Email Fail: ${error.message}`);
        return false;
    }
}

function startMonitoring() {
    checkTimeouts();
    setInterval(checkTimeouts, CHECK_INTERVAL_MS);
}

function getMonitorStatus() {
    return {
        isActive: true,
        lastRun: lastRun ? lastRun.toISOString() : 'never',
        timestamp: new Date().toISOString()
    };
}

module.exports = { startMonitoring, getMonitorStatus, sendEmail };
