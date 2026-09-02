// Mondoo demo file — deliberately vulnerable.
//
// Findings appear as you look at this file. Try the light bulb (Alt+Enter) on a
// highlighted line to apply a fix or dismiss the finding with a reason.
//
// Delete this file whenever you like.
const express = require('express');
const { exec } = require('child_process');
const fs = require('fs');

const app = express();

// Hardcoded credential.
const DB_PASSWORD = 'hunter2-super-secret';

app.get('/ping', (req, res) => {
  // Command injection: user input reaches a shell.
  exec('ping -c 1 ' + req.query.host, (err, stdout) => res.send(stdout));
});

app.get('/file', (req, res) => {
  // Path traversal: user input reaches the filesystem.
  res.send(fs.readFileSync('/var/data/' + req.query.name));
});

app.post('/calc', (req, res) => {
  // Code injection.
  res.send(String(eval(req.body.expression)));
});

module.exports = { app, DB_PASSWORD };
