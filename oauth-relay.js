#!/usr/bin/env node

/**
 * OpenAI OAuth Relay Server
 * 
 * This server:
 * 1. Receives OAuth callbacks from the phone app
 * 2. Forwards them to the opencode plugin
 * 
 * Usage: node oauth-relay.js
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 1456;
const PLUGIN_PORT = 1455;

console.log('🔄 OpenAI OAuth Relay Server starting...');
console.log(`   Listening on port ${PORT}`);
console.log(`   Forwarding to localhost:${PLUGIN_PORT}`);

// Create HTTP server
const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  
  console.log(`📥 Incoming request: ${req.method} ${url.pathname}`);
  
  if (url.pathname === '/auth/callback' && req.method === 'GET') {
    // Extract query parameters
    const code = url.searchParams.get('code');
    const state = url.searchParams.get('state');
    const error = url.searchParams.get('error');
    
    console.log(`   Code: ${code ? '✓' : '✗'}`);
    console.log(`   State: ${state ? '✓' : '✗'}`);
    
    if (error) {
      console.log(`   Error: ${error}`);
      res.writeHead(400, { 'Content-Type': 'text/html' });
      res.end(`
        <!DOCTYPE html>
        <html>
        <head><title>Auth Error</title></head>
        <body style="font-family: Arial; text-align: center; padding: 50px;">
          <h1>❌ Authentication Error</h1>
          <p>${error}</p>
        </body>
        </html>
      `);
      return;
    }
    
    if (!code) {
      res.writeHead(400, { 'Content-Type': 'text/html' });
      res.end('<h1>Missing authorization code</h1>');
      return;
    }
    
    // Forward to opencode plugin
    const forwardUrl = `http://localhost:${PLUGIN_PORT}/auth/callback?code=${code}&state=${state || ''}`;
    
    console.log(`📤 Forwarding to opencode plugin...`);
    
    http.get(forwardUrl, (forwardRes) => {
      console.log(`   Plugin responded: ${forwardRes.statusCode}`);
      
      let data = '';
      forwardRes.on('data', chunk => data += chunk);
      forwardRes.on('end', () => {
        // Send success page to phone
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(`
          <!DOCTYPE html>
          <html>
          <head>
            <title>Auth Complete</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { font-family: Arial, sans-serif; text-align: center; padding: 50px; background: #f5f5f5; }
              .container { background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
              h1 { color: #4CAF50; }
              .success { background: #e8f5e9; padding: 15px; border-radius: 5px; margin-top: 20px; }
            </style>
          </head>
          <body>
            <div class="container">
              <h1>✅ Authorization Complete</h1>
              <p>The auth code has been forwarded to OpenCode.</p>
              <div class="success">
                <strong>Response from plugin:</strong><br>
                ${data.substring(0, 500)}
              </div>
              <p style="margin-top: 20px; color: #666;">
                You can close this page and return to your terminal.
              </p>
            </div>
          </body>
          </html>
        `);
      });
    }).on('error', (err) => {
      console.error(`   Forward error: ${err.message}`);
      res.writeHead(500, { 'Content-Type': 'text/html' });
      res.end(`
        <h1>❌ Forward Error</h1>
        <p>Could not forward to plugin: ${err.message}</p>
      `);
    });
    
  } else if (url.pathname === '/health') {
    // Health check endpoint
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', timestamp: new Date().toISOString() }));
    
  } else if (url.pathname === '/') {
    // Info page
    res.writeHead(200, { 'Content-Type': 'text/html' });
    res.end(`
      <!DOCTYPE html>
      <html>
      <head><title>OAuth Relay</title></head>
      <body style="font-family: monospace; padding: 20px;">
        <h1>🔄 OpenAI OAuth Relay</h1>
        <p>This server forwards OAuth callbacks from the phone app to the OpenCode plugin.</p>
        <p>Endpoints:</p>
        <ul>
          <li><strong>/auth/callback</strong> - Receives OAuth callbacks</li>
          <li><strong>/health</strong> - Health check</li>
        </ul>
        <p>Status: <span style="color: green;">Running</span></p>
      </body>
      </html>
    `);
    
  } else {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
  }
});

server.listen(PORT, () => {
  console.log(`✅ Relay server ready on http://localhost:${PORT}`);
  console.log(`\n📱 Phone should send callbacks to:`);
  console.log(`   http://YOUR_VPS_IP:1456/auth/callback`);
  console.log(`\n🔧 Then this server forwards to:`);
  console.log(`   http://localhost:${PLUGIN_PORT}/auth/callback`);
});

// Graceful shutdown
process.on('SIGINT', () => {
  console.log('\n🛑 Shutting down...');
  server.close(() => {
    console.log('✅ Server closed');
    process.exit(0);
  });
});
