'use strict';

// Transport/clock examples only: no PWA/Android player or authentication implementation.
const assert = require('node:assert/strict');
const test = require('node:test');
const golden = require('./golden.json');

function propertyPayload(envelope) {
  const name = Buffer.from('PARTY_V1', 'ascii');
  const value = Buffer.from(JSON.stringify(envelope), 'utf8');
  assert.ok(value.length <= 8192);
  const out = Buffer.alloc(4 + name.length + value.length);
  out.writeUInt16BE(name.length, 0);
  out.writeUInt16BE(value.length, 2);
  name.copy(out, 4);
  value.copy(out, 4 + name.length);
  return out;
}

function clientFrame(envelope) {
  const payload = propertyPayload(envelope);
  const header = Buffer.alloc(16); // existing event timestamp/id/padding are unrelated to Party.
  header[0] = 1;
  header.writeUIntBE(payload.length, 1, 3);
  return Buffer.concat([header, payload]);
}

function decodeServerFrame(frame) {
  assert.equal(frame[0], 1);
  assert.equal(frame.readUIntBE(1, 3), frame.length - 4);
  const payload = frame.subarray(4);
  const nameBytes = payload.readUInt16BE(0);
  const valueBytes = payload.readUInt16BE(2);
  assert.equal(payload.length, 4 + nameBytes + valueBytes);
  assert.equal(payload.subarray(4, 4 + nameBytes).toString('ascii'), 'PARTY_V1');
  assert.ok(valueBytes <= 8192);
  return JSON.parse(payload.subarray(4 + nameBytes).toString('utf8'));
}

function localDeadline(command, clockModel) {
  assert.equal(clockModel.confident, true);
  return command.executeAtServerMonoMs - clockModel.serverMinusClientMs;
}

function predictedPosition(snapshot, serverMonoMs) {
  let position = snapshot.anchorPositionMs;
  let at = snapshot.anchorServerMonoMs;
  let state = snapshot.state;
  if (snapshot.pendingCommand && serverMonoMs >= snapshot.pendingCommand.executeAtServerMonoMs) {
    position = snapshot.pendingCommand.targetPositionMs;
    at = snapshot.pendingCommand.executeAtServerMonoMs;
    state = snapshot.pendingCommand.targetState;
  }
  return Math.min(snapshot.durationMs, Math.max(0,
    position + (state === 'PLAYING' ? Math.max(0, serverMonoMs - at) : 0)));
}

module.exports = { clientFrame, decodeServerFrame, localDeadline, predictedPosition };

test('golden client framing preserves the existing 16-byte UI envelope', () => {
  const frame = clientFrame(golden.hello);
  assert.equal(frame[0], 1);
  assert.equal(frame.readUIntBE(1, 3), frame.length - 16);
  assert.equal(frame.readUInt16BE(16), 8);
  assert.equal(frame.subarray(20, 28).toString('ascii'), 'PARTY_V1');
  assert.deepEqual(JSON.parse(frame.subarray(28).toString('utf8')), golden.hello);
});

test('server commands use the four-byte command header, not the client event header', () => {
  const payload = propertyPayload(golden.command);
  const header = Buffer.alloc(4);
  header[0] = 1; header.writeUIntBE(payload.length, 1, 3);
  assert.deepEqual(decodeServerFrame(Buffer.concat([header, payload])), golden.command);
});

test('PWA performance.now and Android elapsedRealtime adapters translate the same deadline', () => {
  const pwaPerformanceNow = 100000;
  const androidElapsedRealtime = 100000;
  const deadline = localDeadline(golden.command, golden.clockModel);
  assert.equal(deadline - pwaPerformanceNow, 650);
  assert.equal(deadline - androidElapsedRealtime, 650);
});

test('snapshot supersession does not apply the future anchor before its boundary', () => {
  assert.equal(predictedPosition(golden.snapshot, 100000), 0);
  assert.equal(predictedPosition(golden.snapshot, 100750), 10000);
  assert.equal(predictedPosition(golden.snapshot, 200000), 10000);
});

test('Stop is a local status, never a shared command', () => {
  assert.equal(golden.localStop.type, 'STATUS');
  assert.equal(golden.localStop.status, 'LOCALLY_STOPPED');
  assert.equal(golden.invalidStopReply.code, 'INVALID_ACTION');
  assert.notEqual(golden.command.cause, 'STOP');
});
