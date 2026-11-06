'use strict';

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
  const header = Buffer.alloc(16);
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
  return JSON.parse(payload.subarray(4 + nameBytes).toString('utf8'));
}

function localDeadline(message, clockModel) {
  assert.equal(clockModel.confident, true);
  return message.executeAtServerMonoMs - clockModel.serverMinusClientMs;
}

function predictedPosition(snapshot, serverMonoMs) {
  let position = snapshot.anchorPositionMs;
  let anchor = snapshot.anchorServerMonoMs;
  let state = snapshot.state;
  if (snapshot.pendingCommand && serverMonoMs >= snapshot.pendingCommand.executeAtServerMonoMs) {
    position = snapshot.pendingCommand.targetPositionMs;
    anchor = snapshot.pendingCommand.executeAtServerMonoMs;
    state = snapshot.pendingCommand.targetState;
  }
  if (state === 'PLAYING') position += Math.max(0, serverMonoMs - anchor);
  return Math.min(snapshot.playableEndMs, Math.max(0, position));
}

function joinWithCode(common, code, invitation) {
  assert.match(code, /^[0-9]{3}$/);
  return {
    ...common,
    schemaVersion: 1,
    type: 'JOIN',
    joinCode: code,
    ...(invitation ? {
      partyId: invitation.partyId,
      mediaFileId: invitation.mediaFileId
    } : {})
  };
}

function playerHeartbeat(common, snapshot, observation) {
  return {
    ...common,
    schemaVersion: 1,
    type: 'HEARTBEAT',
    partyId: snapshot.partyId,
    stateVersion: snapshot.stateVersion,
    sampleMonoMs: observation.sampleMonoMs,
    positionMs: observation.positionMs,
    bufferAheadMs: observation.bufferAheadMs,
    state: observation.state
  };
}

module.exports = {
  clientFrame,
  decodeServerFrame,
  localDeadline,
  predictedPosition,
  joinWithCode,
  playerHeartbeat
};

test('client framing preserves the existing 16-byte UI envelope', () => {
  const frame = clientFrame(golden.hello);
  assert.equal(frame[0], 1);
  assert.equal(frame.readUIntBE(1, 3), frame.length - 16);
  assert.equal(frame.readUInt16BE(16), 8);
  assert.equal(frame.subarray(20, 28).toString('ascii'), 'PARTY_V1');
  assert.deepEqual(JSON.parse(frame.subarray(28).toString('utf8')), golden.hello);
});

test('server messages use the four-byte command header', () => {
  const payload = propertyPayload(golden.command);
  const header = Buffer.alloc(4);
  header[0] = 1;
  header.writeUIntBE(payload.length, 1, 3);
  assert.deepEqual(decodeServerFrame(Buffer.concat([header, payload])), golden.command);
});

test('availability declares ordinary SageTV playback and source resolution', () => {
  assert.equal(golden.available.contract, 'PARTY_V1_COORDINATOR');
  assert.equal(golden.available.playbackPath, 'SAGETV_WATCH');
  assert.equal(golden.available.sourceResolution, 'WIZARD_MEDIAFILE');
});

test('client monotonic clocks translate one server deadline', () => {
  const deadline = localDeadline(golden.command, golden.clockModel);
  assert.equal(deadline, 100645.5);
});

test('snapshot projection uses only the authoritative anchor and pending command', () => {
  assert.equal(predictedPosition(golden.snapshot, 100000), 0);
  assert.equal(predictedPosition({
    ...golden.snapshot,
    pendingCommand: golden.command
  }, 100900), 150);
});

test('selected invitees still enter the three-digit code', () => {
  const common = {
    requestId: golden.join.requestId,
    requestSeq: golden.join.requestSeq,
    serverEpoch: golden.join.serverEpoch,
    sessionToken: golden.join.sessionToken
  };
  assert.deepEqual(joinWithCode(common, '042', golden.invited), golden.join);
  assert.throws(() => joinWithCode(common, '42', golden.invited));
});

test('preparation uses the production playback path and remains held', () => {
  assert.equal(golden.prepareAccepted.holdPlayback, true);
  assert.equal(golden.preparationResult.status, 'LOADED');
  assert.equal(golden.preparationResult.watchCode, 0);
  assert.equal(golden.available.playbackPath, 'SAGETV_WATCH');
});

test('zero paused buffer is valid for initial readiness', () => {
  assert.equal(golden.ready.bufferAheadMs, 0);
  assert.equal(golden.correction.targetState, 'PAUSED');
  assert.equal(golden.correction.kind, 'HARD');
});

test('duplicate readiness is an idempotent acknowledgement', () => {
  assert.equal(golden.readyAck.type, 'READY_ACK');
  assert.equal(golden.readyAck.stateVersion, golden.ready.stateVersion);
  assert.equal(golden.readyAck.commandSeq, golden.correction.commandSeq);
  assert.equal(golden.readyAck.executeAtServerMonoMs, undefined);
});

test('commands carry generation, target and future monotonic deadline', () => {
  assert.equal(golden.command.stateVersion, 1);
  assert.equal(golden.command.commandSeq, 1);
  assert.equal(golden.command.targetState, 'PLAYING');
  assert.ok(golden.command.executeAtServerMonoMs > golden.snapshot.serverMonoMs);
});

test('heartbeat reports observed player media time, state and buffer telemetry', () => {
  const common = {
    requestId: golden.heartbeat.requestId,
    requestSeq: golden.heartbeat.requestSeq,
    serverEpoch: golden.heartbeat.serverEpoch,
    sessionToken: golden.heartbeat.sessionToken
  };
  assert.deepEqual(playerHeartbeat(common, {
    partyId: golden.heartbeat.partyId,
    stateVersion: golden.heartbeat.stateVersion
  }, golden.heartbeat), golden.heartbeat);
  assert.equal(golden.heartbeatAck.desiredState, 'PLAYING');
});

test('local Stop is a heartbeat state and preserves the room code', () => {
  assert.equal(golden.localStop.type, 'HEARTBEAT');
  assert.equal(golden.localStop.state, 'STOPPED');
  assert.equal(golden.joinCodeReply.joinCode, '042');
  assert.equal(golden.hostChanged.hostParticipantId, 'guest');
});
