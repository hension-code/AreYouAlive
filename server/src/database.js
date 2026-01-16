const { Sequelize, DataTypes } = require('sequelize');
const crypto = require('crypto');
const path = require('path');

// Initialize SQLite Database
const sequelize = new Sequelize({
  dialect: 'sqlite',
  storage: path.join(__dirname, '../database.sqlite'),
  logging: false
});

// Encryption Settings
const ALGORITHM = 'aes-256-cbc';
// Priority: Environment Variable -> Fallback Hardcoded Key
const KEY_SOURCE = process.env.SECRET_KEY || 'areyoualive-secret-default-key';
const SECRET_KEY = crypto.scryptSync(KEY_SOURCE, 'salt', 32);
const IV_LENGTH = 16;

function encrypt(text) {
  if (!text) return null;
  const iv = crypto.randomBytes(IV_LENGTH);
  const cipher = crypto.createCipheriv(ALGORITHM, SECRET_KEY, iv);
  let encrypted = cipher.update(text);
  encrypted = Buffer.concat([encrypted, cipher.final()]);
  return iv.toString('hex') + ':' + encrypted.toString('hex');
}

function decrypt(text) {
  if (!text) return null;
  const parts = text.split(':');
  const iv = Buffer.from(parts.shift(), 'hex');
  const encryptedText = Buffer.from(parts.join(':'), 'hex');
  const decipher = crypto.createDecipheriv(ALGORITHM, SECRET_KEY, iv);
  let decrypted = decipher.update(encryptedText);
  decrypted = Buffer.concat([decrypted, decipher.final()]);
  return decrypted.toString();
}

// User Model
const User = sequelize.define('User', {
  deviceId: {
    type: DataTypes.STRING,
    primaryKey: true,
    allowNull: false
  },
  userName: {
    type: DataTypes.STRING,
    defaultValue: 'User'
  },
  timeoutHours: {
    type: DataTypes.INTEGER,
    defaultValue: 24
  },
  lastHeartbeat: {
    type: DataTypes.DATE,
    defaultValue: DataTypes.NOW
  },
  // Encrypted Fields
  encryptedSenderEmail: {
    type: DataTypes.TEXT,
    allowNull: true
  },
  encryptedSenderPassword: {
    type: DataTypes.TEXT,
    allowNull: true
  },
  encryptedEmergencyEmail: {
    type: DataTypes.TEXT,
    allowNull: true
  },
  isAlerting: {
    type: DataTypes.BOOLEAN,
    defaultValue: false
  },
  lastAlertTime: {
    type: DataTypes.DATE,
    allowNull: true
  }
});

// Helper methods to set/get decrypted values
User.prototype.setCredentials = function (email, password, emergency) {
  this.encryptedSenderEmail = encrypt(email);
  this.encryptedSenderPassword = encrypt(password);
  this.encryptedEmergencyEmail = encrypt(emergency);
};

User.prototype.getDecryptedCredentials = function () {
  return {
    email: decrypt(this.encryptedSenderEmail),
    password: decrypt(this.encryptedSenderPassword),
    emergencyEmail: decrypt(this.encryptedEmergencyEmail)
  };
};

module.exports = { sequelize, User };
