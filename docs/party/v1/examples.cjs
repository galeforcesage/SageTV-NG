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
  return Math.min(snapshot.playableEndMs ?? snapshot.durationMs, Math.max(0,
    position + (state === 'PLAYING' ? Math.max(0, serverMonoMs - at) : 0)));
}

function mediaBindingCommand(handleReply) {
  assert.equal(handleReply.type, 'MEDIA_ADMISSION_HANDLE');
  assert.match(handleReply.handle, /^[A-Za-z0-9_-]{32}$/);
  return Buffer.from(`PARTY_BIND ${handleReply.handle}\r\n`, 'ascii');
}

function executionAck(command, commonRequest, observation) {
  return {
    ...commonRequest, schemaVersion: 1, type: 'ACK',
    partyId: command.partyId, stateVersion: command.stateVersion,
    commandSeq: command.commandSeq, stage: 'EXECUTED',
    success: observation.success, sampleMonoMs: observation.sampleMonoMs,
    positionMs: observation.positionMs, state: observation.state
  };
}

function joinWithCode(commonRequest, enteredCode, invitation) {
  assert.match(enteredCode, /^[0-9]{3}$/);
  return { ...commonRequest, schemaVersion: 1, type: 'JOIN', joinCode: enteredCode,
    ...(invitation ? { partyId: invitation.partyId, mediaFileId: invitation.mediaFileId } : {}) };
}

function prepareJoined(commonRequest, joined, explicitlyConfirmed, selection) {
  assert.equal(explicitlyConfirmed, true);
  assert.equal(joined.prepareVia, 'PARTY_PREPARE');
  return { ...commonRequest, schemaVersion: 1, type: 'PREPARE', partyId: joined.partyId,
    intent: 'JOIN_AND_PREPARE', confirmReplace: true,
    ...(selection ? { selectionId: selection.selectionId } : {}) };
}

function selectedMsePath(selection, loaded) {
  assert.equal(selection.status, 'SELECTED');
  assert.equal(loaded.status, 'LOADED');
  assert.equal(loaded.selectionId, selection.selectionId);
  assert.equal(loaded.pathGeneration, selection.pathGeneration);
  assert.equal(loaded.delivery.surface, selection.preparation.surface);
  assert.equal(loaded.delivery.route, 'mse');
  assert.equal(loaded.delivery.mode, 'pull-xcode');
  assert.equal(loaded.delivery.container, 'MP4');
  assert.ok(['browserhd_remux', 'browserhd_copyv', 'browserhd'].includes(loaded.delivery.xcodeMode));
  return loaded.delivery; // Not admission/readiness or a decoder-observed output proof.
}

function validateNativeReissue(previous, next) {
  // API values already verified by Java against the actual owned gfx Socket, not HTTP inputs.
  assert.notEqual(next.contextId, previous.contextId);
  assert.equal(next.ownershipGeneration, previous.ownershipGeneration + 1);
  for (const field of ['serverEpoch', 'rendererHandle', 'mediaFileId', 'segmentIndex',
    'resourcePath', 'surface', 'mode', 'xcodeMode', 'playbackGeneration', 'pathGeneration', 'durationMs'])
    assert.equal(next[field], previous[field], field);
}

module.exports = { clientFrame, decodeServerFrame, localDeadline, predictedPosition,
  mediaBindingCommand, executionAck, joinWithCode, prepareJoined, selectedMsePath, validateNativeReissue };

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

test('future thin bridge echoes the opaque handle on only its post-OPEN producer', () => {
  assert.equal(mediaBindingCommand(golden.mediaAdmissionHandle).toString('ascii'),
    `PARTY_BIND ${golden.mediaAdmissionHandle.handle}\r\n`);
  assert.throws(() => mediaBindingCommand({ ...golden.mediaAdmissionHandle, handle: 'bad\r\nQUIT' }));
  assert.equal(golden.mediaAdmissionHandle.expiresInMs, 60000);
  // Future HLS/bridge request header; never put the credential into a logged media URL.
  const headers = { 'X-SageTV-Party-Admission': golden.mediaAdmissionHandle.handle };
  assert.equal(headers['X-SageTV-Party-Admission'].length, 32);
});

test('execution is a separate observed result, not a received command', () => {
  const expected = golden.ackExecuted;
  assert.deepEqual(executionAck(golden.command, {
    requestId: expected.requestId, requestSeq: expected.requestSeq,
    serverEpoch: expected.serverEpoch, sessionToken: expected.sessionToken
  }, expected), expected);
  assert.equal(golden.ackReceived.stage, 'RECEIVED');
  assert.equal(expected.sampleMonoMs + golden.clockModel.serverMinusClientMs, 100780);
});

test('outcome spread includes uncertainty and failed landing rather than fabricating sync proof', () => {
  const outcome = golden.commandOutcome;
  const observed = outcome.participants.map(p => p.executedAtServerMonoMs);
  assert.equal(Math.max(...observed) - Math.min(...observed), outcome.executionSpreadMs);
  assert.equal(outcome.spreadUncertaintyMs,
    2 * Math.max(...outcome.participants.map(p => p.uncertaintyMs)));
  assert.equal(outcome.failedCount, 1);
  assert.equal(outcome.expectedCount - outcome.executedCount, outcome.missingCount);
  assert.equal(outcome.reportDeadlineServerMonoMs - outcome.executeAtServerMonoMs, 10000);
});

test('majority buffering recommendation is advisory under keep-moving policy', () => {
  const recommendation = golden.bufferRecommendation;
  assert.ok(recommendation.bufferingCount * 2 > recommendation.activeCount);
  assert.equal(recommendation.policy, 'KEEP_MOVING');
  assert.equal(recommendation.type, 'BUFFER_RECOMMENDATION');
  assert.equal(recommendation.stateVersion, golden.command.stateVersion);
  assert.equal(recommendation.commandSeq, golden.command.commandSeq);
});

test('selected invitees still enter three digits on their existing admitted session', () => {
  const { schemaVersion, type, partyId, mediaFileId, joinCode, ...common } = golden.join;
  assert.deepEqual(joinWithCode(common, '042', golden.invited), golden.join);
  assert.equal(joinWithCode(common, '042').partyId, undefined);
  assert.equal(golden.invited.joinCodeRequired, true);
  assert.equal(golden.invited.joinCode, undefined);
  assert.equal(golden.invited.joinToken, undefined);
  assert.throws(() => joinWithCode(common, '42', golden.invited));
  assert.equal(golden.joinRateLimited.retryAfterMs, 60000);
  assert.equal(golden.joinCodeReply.expiresAtServerMonoMs - golden.joinCodeReply.serverMonoMs, 120000);
});

test('Current Position starts paused at the server anchor, never at inherited resume or zero', () => {
  assert.equal(golden.createCurrent.startMode, 'CURRENT_POSITION');
  assert.equal(golden.createCurrent.positionMs, undefined);
  assert.equal(golden.createdCurrent.startPositionMs, 123456);
  assert.equal(predictedPosition(golden.createdCurrent.snapshot, 200000), 123456);
  assert.equal(golden.createdCurrent.snapshot.commandSeq, 0);
});

test('Join & Prepare requires explicit confirmation and loaded is not media admission/readiness', () => {
  const { partyId, intent, confirmReplace, ...common } = golden.prepare;
  assert.deepEqual(prepareJoined(common, golden.createdCurrent, true), golden.prepare);
  assert.throws(() => prepareJoined(common, golden.createdCurrent, false));
  assert.equal(golden.prepareAccepted.holdPlayback, true);
  assert.equal(golden.preparationLoaded.preparationId, golden.prepareAccepted.preparationId);
  assert.equal(golden.preparationLoaded.playbackGeneration, 7);
  assert.equal(golden.preparationFailed.watchCode, -3);
  assert.equal(golden.preparationLoaded.type, 'PREPARATION_RESULT');
  assert.notEqual(golden.preparationLoaded.type, 'MEDIA_ADMISSION_HANDLE');
  assert.notEqual(golden.preparationLoaded.type, 'READY');
});

test('Party-only MSE preparation echoes selection and validates the actual conditioned path', () => {
  const prepared = prepareJoined(golden.prepare, golden.createdCurrent, true, golden.surfaceSelected);
  assert.equal(prepared.selectionId, golden.surfaceSelectionAccepted.selectionId);
  assert.deepEqual(selectedMsePath(golden.surfaceSelected, golden.selectedPreparationLoaded),
    golden.surfaceSelected.preparation);
  assert.throws(() => selectedMsePath(golden.surfaceSelected,
    { ...golden.selectedPreparationLoaded, pathGeneration: 2 }));
  assert.throws(() => selectedMsePath(golden.surfaceSelected,
    { ...golden.selectedPreparationLoaded, delivery: { ...golden.selectedPreparationLoaded.delivery, container: 'MPEG2-TS' } }));
  assert.throws(() => selectedMsePath(golden.surfaceSelected,
    { ...golden.selectedPreparationLoaded, delivery: { ...golden.selectedPreparationLoaded.delivery, mode: 'pull' } }));
});

test('admission context correlates the exact request and generation without sharing a handle on revoke', () => {
  const admission = golden.mediaAdmissionHandle;
  assert.equal(admission.playbackGeneration, golden.selectedPreparationLoaded.playbackGeneration);
  assert.equal(admission.pathGeneration, golden.selectedPreparationLoaded.pathGeneration);
  assert.equal(admission.expiresAtServerMonoMs - admission.serverMonoMs, admission.expiresInMs);
  assert.equal(admission.contextId, golden.mediaAdmissionRevoked.contextId);
  assert.equal(golden.mediaAdmissionRevoked.handle, undefined);
  assert.equal(typeof admission.requestSeq, 'number');
  // Java bridge must verify with claim(handle, its actual owned Socket); this JSON is not HTTP authority.
});

test('ordinary native reissue preserves the immutable server resource, not caller file or mode', () => {
  const previous = golden.nativeOwnershipContext;
  const next = { ...previous, contextId: 'example-next-native-context',
    ownershipGeneration: 1, expiresAtServerMonoMs: 200000 };
  validateNativeReissue(previous, next);
  assert.throws(() => validateNativeReissue(previous, { ...next, resourcePath: 'another-file' }));
  assert.throws(() => validateNativeReissue(previous, { ...next, mode: 'pull' }));
  assert.throws(() => validateNativeReissue(previous, { ...next, segmentIndex: 0 }));
  assert.throws(() => validateNativeReissue(previous, previous));
  assert.equal(previous.type, undefined); // Not a Party or HTTP credential envelope.
  // release/isCurrent=false never proves teardown; await the bridge's real resource terminal future.
});
