#!/usr/bin/env node

/**
 * OpenCode Auto-Config Server
 *
 * Handles OAuth flow for OpenCode CLI on remote VPS
 *
 * Usage:
 *   node auto-config-server.js --tunnel-url https://xxx.cloudflared.io
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const PORT = process.env.PORT || 2088;
let authCode = null;
let authCallbackResolve = null;

console.log('🔧 OpenCode Auto-Config Server');

const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://localhost:${PORT}`);
    console.log(`📥 ${req.method} ${url.pathname}`);

    // OAuth callback endpoint - phone sends auth code here
    if (url.pathname === '/auth/callback' && req.method === 'GET') {
        const code = url.searchParams.get('code');
        if (code) {
            authCode = code;
            console.log('✅ Auth code received');
            res.writeHead(200, { 'Content-Type': 'text/plain' });
            res.end('OK - Auth code received. You can close this server now.');

            if (authCallbackResolve) {
                authCallbackResolve(code);
                authCallbackResolve = null;
            }
        } else {
            res.writeHead(400, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'Missing code parameter' }));
        }
        return;
    }

    // Status endpoint
    if (url.pathname === '/status' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            hasAuthCode: authCode !== null,
            timestamp: new Date().toISOString()
        }));
        return;
    }

    // Health check
    if (url.pathname === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'ok' }));
        return;
    }

    // Root - show info
    if (url.pathname === '/') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(`
            <!DOCTYPE html>
            <html>
            <head><title>OpenCode Auth</title></head>
            <body style="font-family: Arial; padding: 30px;">
                <h1>OpenCode Auth Server</h1>
                <p>Status: ${authCode ? '✅ Authenticated' : '⏳ Waiting for auth...'}</p>
                <p>Keep this page open until auth completes.</p>
            </body>
            </html>
        `);
        return;
    }

    res.writeHead(404);
    res.end('Not Found');
});

function startServer() {
    return new Promise((resolve) => {
        server.listen(PORT, '127.0.0.1', () => {
            console.log(`✅ Server ready on port ${PORT}`);
            resolve();
        });
    });
}

function waitForAuth(timeout = 300000) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            reject(new Error('Auth timeout'));
        }, timeout);

        authCallbackResolve = (code) => {
            clearTimeout(timer);
            resolve(code);
        };
    });
}

async function main() {
    const tunnelUrl = process.argv[2];

    if (!tunnelUrl) {
        console.log('Usage: node auto-config-server.js <cloudflare-tunnel-url>');
        console.log('');
        console.log('Example:');
        console.log('  1. Start tunnel: cloudflared tunnel --url http://localhost:2088');
        console.log('  2. Copy the HTTPS URL');
        console.log('  3. Run: node auto-config-server.js https://xxx.cloudflared.io');
        console.log('');
        console.log('The server will print a deep link to send to your phone.');
        process.exit(1);
    }

    await startServer();

    const deepLink = `openai-auth-bridge://configure?url=${encodeURIComponent(tunnelUrl)}`;
    const callbackUrl = `${tunnelUrl}/auth/callback`;

    console.log('');
    console.log('📱 Send this link to your phone:');
    console.log('');
    console.log(deepLink);
    console.log('');
    console.log('Or copy the URL below and open it on your phone:');
    console.log(callbackUrl);
    console.log('');
    console.log('Waiting for auth... (Ctrl+C to cancel)');

    try {
        await waitForAuth();
        console.log('');
        console.log('✅ Authentication complete!');
        console.log('You can now use OpenCode CLI.');
        process.exit(0);
    } catch (e) {
        console.log(`❌ ${e.message}`);
        process.exit(1);
    }
}

main();
