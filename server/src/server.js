require('dotenv').config();
const express = require('express');
const cors = require('cors');
const { sequelize, User } = require('./database');
const { startMonitoring, getMonitorStatus } = require('./monitor');
const { logActivity } = require('./logger');

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

// Init DB and Start Server
sequelize.sync().then(() => {
    console.log('Database synced');

    app.listen(PORT, '0.0.0.0', () => {
        console.log(`Server running on port ${PORT}`);
        // Start the background monitoring loop
        startMonitoring();
    });
}).catch(err => console.error('DB Config Error:', err));

// Routes

// 1. Register / Update Config
app.post('/api/register', async (req, res) => {
    try {
        const { deviceId, userName, timeoutHours, emergencyEmail } = req.body;

        if (!deviceId) return res.status(400).json({ error: 'Device ID required' });

        let user = await User.findByPk(deviceId);
        if (!user) {
            user = User.build({ deviceId });
        }

        user.userName = userName || user.userName;
        user.timeoutHours = timeoutHours || 24;
        // Reset heartbeat on config update to be safe
        user.lastHeartbeat = new Date();

        if (emergencyEmail) {
            // Just encrypt emergency email, pass null for others
            user.setCredentials(null, null, emergencyEmail);
        }

        await user.save();
        logActivity(user.deviceId, user.userName, `Register/UpdateConfig (Timeout: ${user.timeoutHours}h)`);
        res.json({ status: 'ok', message: 'User configured' });
    } catch (error) {
        console.error(error);
        res.status(500).json({ error: 'Internal Server Error' });
    }
});

// 2. Heartbeat (I am alive)
app.post('/api/heartbeat', async (req, res) => {
    try {
        const { deviceId } = req.body;
        if (!deviceId) return res.status(400).json({ error: 'Device ID required' });

        const user = await User.findByPk(deviceId);
        if (!user) return res.status(404).json({ error: 'User not registered' });

        const wasAlerting = user.isAlerting;

        user.lastHeartbeat = new Date();
        user.isAlerting = false; // Reset alert state
        await user.save();
        logActivity(user.deviceId, user.userName, 'Heartbeat (Live)');

        // If user was in alert state, send a recovery email immediately
        if (wasAlerting) {
            console.log(`User ${user.userName} has recovered. Sending Resolved email...`);
            const { sendEmail } = require('./monitor');
            sendEmail(user, 'resolved'); // Async call, don't need to await for response
        }

        res.json({
            status: 'ok',
            lastHeartbeat: user.lastHeartbeat,
            wasAlerting: wasAlerting
        });
    } catch (error) {
        console.error(error);
        res.status(500).json({ error: 'Internal Server Error' });
    }
});

// 3. Ping (Connection Test)
app.get('/api/ping', (req, res) => {
    res.json({
        status: 'ok',
        message: 'Pong from "Are You Alive" server!',
        monitor: getMonitorStatus(),
        uptime: process.uptime()
    });
});

