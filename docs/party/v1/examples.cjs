'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const golden = require('./golden.json');

test('golden fixtures declare independent scenario boundaries', () => {
  assert.equal(golden._meta.contractRevision, 'v0.10');
  assert.deepEqual(golden._meta.pwaPartySurface, {
    surfaceId: 'pwa_party_cmaf',
    route: 'mse',
    deliveryMode: 'party-cmaf',
    container: 'MP4',
    openUrlPattern: 'iosstream_<clientMac>_<mediaFileId>_<segment>_<bandwidth>_fmp4.m3u8'
  });
  assert.equal(golden._meta.fixturesAreIndependentScenarios, true);
  for (const sequence of Object.values(golden._meta.scenarios)) {
    for (const fixtureName of sequence) assert.ok(Object.hasOwn(golden, fixtureName));
  }
});

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

function mediaCommandFrame(commandId, payload) {
  const json = Buffer.from(JSON.stringify(payload), 'utf8');
  const body = Buffer.alloc(4 + json.length + 1);
  body.writeUInt32BE(json.length + 1, 0);
  json.copy(body, 4);
  const header = Buffer.alloc(4);
  header.writeUInt32BE((commandId << 24) | body.length, 0);
  return Buffer.concat([header, body]);
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

function serverSample(clientMonoMs, clockModel) {
  assert.equal(clockModel.confident, true);
  return Math.round(clientMonoMs + clockModel.serverMinusClientMs);
}

function predictedPosition(snapshot, serverMonoMs) {
  let position = snapshot.anchorPositionMs;
  let anchor = snapshot.anchorServerMonoMs;
  let state = snapshot.state;
  if (!snapshot.barrierPending && snapshot.pendingCommand
      && serverMonoMs >= snapshot.pendingCommand.executeAtServerMonoMs) {
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
  assert.equal(golden.hello.contractRevision, 10);
  assert.equal(golden.available.contract, 'PARTY_V1_COORDINATOR');
  assert.equal(golden.available.contractRevision, 10);
  assert.equal(golden.available.requiredRevisionCapability, 'PARTY_CONTRACT_REV_010');
  assert.equal(golden.available.playbackPath, 'SAGETV_WATCH');
  assert.equal(golden.available.sourceResolution, 'WIZARD_MEDIAFILE');
});

test('HELLO renews session credentials without replacing recovery identity', () => {
  assert.notEqual(golden.renewedAvailable.sessionToken, golden.available.sessionToken);
  assert.equal(golden.renewedAvailable.sessionHandle, golden.available.sessionHandle);
  assert.equal(golden.renewedAvailable.recoveryToken, golden.created.recoveryToken);
  assert.ok(golden.renewedAvailable.sessionTokenExpiresAtServerMonoMs
      > golden.available.sessionTokenExpiresAtServerMonoMs);
  assert.equal(golden.sessionExpiredError.code, 'SESSION_EXPIRED');
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

test('correlated SNAPSHOT reply echoes the request identity', () => {
  assert.equal(golden.snapshotRequest.type, 'SNAPSHOT');
  assert.equal(golden.snapshotReply.type, 'SNAPSHOT');
  assert.equal(golden.snapshotReply.requestId, golden.snapshotRequest.requestId);
  assert.equal(golden.snapshotReply.partyId, golden.snapshotRequest.partyId);
});

test('CREATED includes the complete initial snapshot', () => {
  assert.equal(golden.created.snapshot.type, 'SNAPSHOT');
  assert.equal(golden.created.snapshot.partyId, golden.created.partyId);
  assert.equal(golden.created.snapshot.liveEdgeDelayMs, 0);
  assert.equal(golden.created.snapshot.roster[0].participantId,
      golden.created.participantId);
});

test('invited joining couples invitation identity with the entered code', () => {
  const common = {
    requestId: golden.join.requestId,
    requestSeq: golden.join.requestSeq,
    serverEpoch: golden.join.serverEpoch,
    sessionToken: golden.join.sessionToken
  };
  assert.deepEqual(joinWithCode(common, '042', golden.invited), golden.join);
  assert.throws(() => joinWithCode(common, '42', golden.invited));
});

test('join throttling is explicit and retryable', () => {
  assert.equal(golden.joinRateLimited.code, 'JOIN_RATE_LIMITED');
  assert.ok(golden.joinRateLimited.retryAfterMs > 0);
});

test('preparation uses the production playback path and remains held', () => {
  assert.equal(golden.prepareAccepted.holdPlayback, true);
  assert.equal(golden.prepareAccepted.openReused, false);
  assert.equal(golden.mediaOpenBinding.preparationBinding,
      golden.prepareAccepted.preparationBinding);
  assert.equal(golden.mediaOpenBindingAck.preparationBinding,
      golden.mediaOpenBinding.preparationBinding);
  assert.equal(golden.mediaOpenBindingAckResponse.requestId,
      golden.mediaOpenBindingAck.requestId);
  assert.equal(golden.preparationResult.status, 'LOADED');
  assert.equal(golden.prepareAccepted.preparationBinding,
      golden.preparationResult.preparationBinding);
  assert.equal(golden.adapterMediaOpen.preparationBinding,
      golden.preparationResult.preparationBinding);
  assert.equal(golden.mediaOpened.preparationBinding,
      golden.adapterMediaOpen.preparationBinding);
  assert.equal(golden.mediaOpenedAck.requestId, golden.mediaOpened.requestId);
  assert.equal(golden.prepareAcceptedReused.openReused, true);
  assert.equal(golden.mediaOpenedReused.reused, true);
  assert.equal(golden.mediaOpenedReused.localOpenGeneration,
      golden.mediaOpened.localOpenGeneration);
  assert.equal(golden.preparationResult.watchCode, 0);
  assert.equal(golden.preparationResult.delivery.mode, 'pull');
  assert.notEqual(golden.preparationResult.delivery.mode, 'push');
  assert.equal(golden.available.playbackPath, 'SAGETV_WATCH');
});

test('new media open binding uses strict command 41 framing and one-shot identity', () => {
  const binding = golden.mediaCommandOpenBinding.preparationBinding;
  assert.match(binding, /^[A-Za-z0-9_-]{22}$/);
  assert.equal(Buffer.from(binding, 'base64url').length, 16);
  const payload = {...golden.mediaCommandOpenBinding};
  delete payload.commandId;
  const frame = mediaCommandFrame(golden.mediaCommandOpenBinding.commandId, payload);
  assert.equal(frame.readUInt8(0), 41);
  assert.equal(frame.readUIntBE(1, 3), frame.length - 4);
  assert.equal(frame.readUInt32BE(4), frame.length - 8);
  assert.equal(frame.at(-1), 0);
  assert.equal(golden.mediaCommandOpenBindingAck.ack, 1);
  assert.equal(golden.mediaOpenOrdering.commandOpenOrdinal,
      golden.mediaOpenOrdering.nextExpectedOpenOrdinal);
  assert.equal(golden.mediaOpenOrdering.perSocketSerialized, true);
  assert.equal(golden.mediaOpenOrdering.commandAckBeforeOpen, true);
  assert.equal(golden.mediaOpenOrdering.commandAckBeforeReady, true);
  assert.equal(golden.competingOpenBindingError.code,
      'PREPARATION_BINDING_MISMATCH');
  assert.equal(golden.cancelledBindingOpenError.code, 'CANCELLED_DIRECTIVE');
});

test('direct push installs a versioned epoch origin before payload bytes', () => {
  const command = golden.mediaCommandPushEpoch;
  const frame = Buffer.alloc(32);
  frame.writeUInt32BE((command.commandId << 24) | command.payloadLength, 0);
  frame.writeInt32BE(command.version, 4);
  frame.writeBigInt64BE(BigInt(command.streamEpoch), 8);
  frame.writeBigInt64BE(BigInt(command.landedRecordingPositionMs), 16);
  frame.writeBigInt64BE(BigInt(command.mediaTimestampMs), 24);

  assert.equal(frame.readUInt8(0), 42);
  assert.equal(frame.readUIntBE(1, 3), 28);
  assert.equal(frame.readInt32BE(4), 1);
  assert.equal(frame.readBigInt64BE(8), 7n);
  assert.equal(frame.readBigInt64BE(16), 726400n);
  assert.equal(frame.readBigInt64BE(24), 95400n);
  assert.equal(command.beforeFirstPushBuffer, true);
  assert.equal(golden.mediaCommandPushEpochAck.ack, 1);
});

test('PWA bindings remain isolated when one bridge serves multiple sessions', () => {
  assert.equal(golden.pwaBindingAckA.playbackSessionId,
      golden.pwaBindingAckResponseA.playbackSessionId);
  assert.equal(golden.pwaBindingAckA.preparationBinding,
      golden.pwaBindingAckResponseA.preparationBinding);
  assert.equal(golden.pwaBindingAckA.playbackSessionId,
      golden.pwaHttpOpenA.xPlaybackSessionId);
  assert.equal(golden.pwaBindingAckA.preparationBinding,
      golden.pwaHttpOpenA.xSagePartyPreparationBinding);
  assert.equal(golden.pwaBindingAckB.playbackSessionId,
      golden.pwaHttpOpenB.xPlaybackSessionId);
  assert.equal(golden.pwaBindingAckB.preparationBinding,
      golden.pwaHttpOpenB.xSagePartyPreparationBinding);
  assert.notEqual(golden.pwaBindingAckA.playbackSessionId,
      golden.pwaBindingAckB.playbackSessionId);
  assert.equal(golden.pwaCrossSessionBindingError.httpStatus, 409);
});

test('zero paused buffer is valid for initial readiness', () => {
  assert.equal(golden.ready.bufferAheadMs, 0);
  assert.equal(golden.correction.targetState, 'PAUSED');
  assert.equal(golden.correction.kind, 'NONE');
});

test('NONE reveals an already-held paused frame without a playing-frame callback', () => {
  assert.equal(golden.correction.kind, 'NONE');
  assert.equal(golden.correction.targetState, 'PAUSED');
  assert.equal(golden.nonePresented.state, 'PAUSED');
  assert.equal(golden.nonePresented.positionMs, golden.correction.targetPositionMs);
  assert.equal(golden.nonePresented.frameMonoMs, undefined);
  assert.ok(golden.nonePresented.coverRemovedMonoMs
      >= golden.correction.executeAtServerMonoMs);
  assert.equal(golden.noneExecutionTrace.seekCount, 0);
  assert.equal(golden.noneExecutionTrace.flushCount, 0);
  assert.equal(golden.noneExecutionTrace.newFrameCallbackRequired, false);
  assert.equal(golden.noneExecutionTrace.coverRemovalEvidenceRequired, true);
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
  assert.equal(golden.command.directiveId, golden.ackPresented.directiveId);
  assert.equal(golden.command.targetState, 'PLAYING');
  assert.ok(golden.command.executeAtServerMonoMs > golden.snapshot.serverMonoMs);
});

test('first presented frame settles activation before drift telemetry', () => {
  assert.equal(golden.ackExecuted.stage, 'EXECUTED');
  assert.equal(golden.ackPresented.stage, 'PRESENTED');
  assert.equal(golden.ackPresented.state, 'PLAYING');
  assert.equal(golden.heartbeatAck.activationPending, false);
});

test('disruptive reposition arms before one common reveal deadline', () => {
  assert.equal(golden.barrierCommand.phase, 'PREPARE');
  assert.equal(golden.barrierCommand.barrier, true);
  assert.ok(golden.barrierCommand.executeAtServerMonoMs
      < golden.barrierCommand.visibleAtServerMonoMs);
  assert.equal(golden.ackArmed.stage, 'ARMED');
  assert.equal(golden.ackArmed.state, 'PAUSED');
  assert.equal(golden.ackArmed.directiveId, golden.barrierCommand.directiveId);
  assert.equal(golden.ackArmedResponse.requestId, golden.ackArmed.requestId);
  assert.equal(golden.ackArmedResponse.stage, 'ARMED');
  assert.equal(golden.ackArmedResponse.accepted, true);
  assert.equal(golden.ackArmedGuestResponse.requestId,
      golden.ackArmedGuest.requestId);
  assert.equal(golden.ackArmedGuestResponse.accepted, true);
  assert.equal(golden.ackArmedInaccurateResponse.accepted, false);
  assert.equal(golden.reveal.directiveId, golden.barrierCommand.directiveId);
  assert.equal(golden.reveal.executeAtServerMonoMs,
      golden.barrierCommand.visibleAtServerMonoMs);
  assert.notEqual(golden.barrierCommand.directiveId,
      golden.barrierCommandGuest.directiveId);
  assert.equal(golden.ackArmedGuest.directiveId,
      golden.barrierCommandGuest.directiveId);
  assert.equal(golden.ackHeld.stage, 'HELD');
  assert.equal(golden.ackHeldResponse.accepted, true);
  assert.equal(golden.ackHeldGuestResponse.accepted, true);
  assert.equal(golden.revealGuest.directiveId,
      golden.barrierCommandGuest.directiveId);
  assert.equal(golden.revealGuest.executeAtServerMonoMs,
      golden.reveal.executeAtServerMonoMs);
  assert.equal(golden.barrierPresented.directiveId,
      golden.reveal.directiveId);
  assert.ok(golden.barrierPresented.requestSeq
      > golden.ackHeld.requestSeq);
  assert.equal(golden.barrierPresentedGuest.directiveId,
      golden.revealGuest.directiveId);
  assert.ok(golden.reveal.executeAtServerMonoMs
      >= golden.ackHeldGuestResponse.serverMonoMs + 300);
  assert.ok(golden.lateArmedReveal.executeAtServerMonoMs
      >= golden.lateHeldResponse.serverMonoMs + 300);
  assert.ok(golden.barrierRevealedSnapshot.serverMonoMs
      > golden.ackHeldGuestResponse.serverMonoMs);
});

test('long barrier preparation refreshes held evidence within one activation budget', () => {
  const timing = golden.longPreparationTiming;
  assert.equal(timing.lastArmedAdmissionServerMonoMs
      - timing.firstArmedAdmissionServerMonoMs, 9000);
  assert.ok(timing.revealServerMonoMs < timing.activationDeadlineServerMonoMs);
  assert.ok(timing.revealServerMonoMs
      - timing.firstHeldAdmissionServerMonoMs <= timing.heldFreshnessMs);
  assert.ok(timing.revealServerMonoMs
      - timing.lastHeldAdmissionServerMonoMs <= timing.heldFreshnessMs);
  assert.ok(timing.oldestArmedAgeAtRevealMs > timing.heldFreshnessMs);
  assert.ok(timing.revealServerMonoMs + timing.presentationReserveMs
      <= timing.activationDeadlineServerMonoMs);
});

test('barrier projection remains old until the committed REVEAL deadline', () => {
  assert.equal(golden.barrierPreparingSnapshot.barrierPending, true);
  assert.equal(golden.barrierPreparingSnapshot.pendingCommand, undefined);
  assert.equal(predictedPosition(golden.barrierPreparingSnapshot, 104600), 3745);
  assert.equal(golden.barrierRevealedSnapshot.pendingCommand.executeAtServerMonoMs,
      golden.reveal.executeAtServerMonoMs);
  assert.equal(predictedPosition(golden.barrierRevealedSnapshot, 104599), 3744);
  assert.equal(predictedPosition(golden.barrierRevealedSnapshot, 104600), 120000);
});

test('covered PLAYING evidence joins frame and cover-removal boundaries', () => {
  assert.ok(golden.barrierPresented.frameMonoMs
      >= golden.barrierPresented.coverRemovedMonoMs);
  assert.equal(golden.barrierPresented.sampleMonoMs,
      golden.barrierPresented.frameMonoMs);
  assert.ok(golden.barrierPresented.sampleUncertaintyMs > 0);
  assert.equal(predictedPosition(golden.barrierRevealedSnapshot,
      golden.barrierPresented.frameMonoMs), golden.barrierPresented.positionMs);
  assert.equal(predictedPosition(golden.barrierRevealedSnapshot,
      golden.barrierPresentedGuest.frameMonoMs),
      golden.barrierPresentedGuest.positionMs);
});

test('obsolete catch-up correction is cancelled before rebased replacement', () => {
  assert.equal(golden.cancelObsoleteHard.type, 'CANCEL');
  assert.equal(golden.cancelObsoleteHard.reason, 'REBASE_REQUIRED');
  assert.equal(golden.hardCatchupOriginal.commandSeq,
      golden.rebasedHardCorrection.commandSeq);
  assert.equal(golden.hardCatchupOriginal.recoveryAttemptId,
      golden.rebasedHardCorrection.recoveryAttemptId);
  assert.equal(golden.hardCatchupOriginal.recoveryDeadlineServerMonoMs,
      golden.rebasedHardCorrection.recoveryDeadlineServerMonoMs);
  assert.notEqual(golden.cancelObsoleteHard.directiveId,
      golden.rebasedHardCorrection.directiveId);
  assert.equal(golden.lateRevealRejected.success, false);
  assert.equal(golden.lateRevealRejected.failureCode, 'CANCELLED_DIRECTIVE');
});

test('shared action fences participant recovery without waiting for it', () => {
  assert.equal(golden.cancelRecoveryForSharedAction.reason, 'SHARED_TRANSITION');
  assert.equal(golden.cancelRecoveryForSharedAction.directiveId,
      golden.hardRecoveryBeforeSharedAction.directiveId);
  assert.ok(golden.sharedPauseDuringRecovery.commandSeq
      > golden.hardRecoveryBeforeSharedAction.commandSeq);
  assert.equal(golden.sharedPauseDuringRecovery.targetState, 'PAUSED');
});

test('activation deadline exhaustion cancels instead of consuming PRESENTED reserve', () => {
  assert.equal(golden.activationDeadlineExhaustedCancel.reason,
      'ACTIVATION_TIMEOUT');
  assert.equal(golden.activationDeadlineExhaustedError.code,
      'ACTIVATION_EXPIRED');
});

test('state-only PAUSE dispatches at EXECUTED and settles at PRESENTED', () => {
  assert.equal(golden.pauseCommand.cause, 'PAUSE');
  assert.equal(golden.pauseCommand.targetState, 'PAUSED');
  assert.equal(golden.pauseCommand.barrier, undefined);
  assert.equal(golden.pauseExecuted.stage, 'EXECUTED');
  assert.equal(golden.pauseExecutedResponse.requestId, golden.pauseExecuted.requestId);
  assert.equal(golden.pauseExecutedResponse.directiveId, golden.pauseCommand.directiveId);
  assert.equal(golden.pausePresented.stage, 'PRESENTED');
  assert.equal(golden.pausePresented.state, 'PAUSED');
  assert.ok(golden.pausePresented.sampleUncertaintyMs > 0);
  assert.equal(golden.pausePresented.positionMs, golden.pauseCommand.targetPositionMs);
  assert.equal(golden.pauseMisalignedError.code, 'POSITION_MISMATCH');
  assert.ok(Math.abs(golden.pauseMisalignedPresented.positionMs
      - golden.pauseCommand.targetPositionMs)
      > golden._meta.pauseAlignmentDeadbandMs);
});

test('negative frame ACK does not invent presentation evidence', () => {
  assert.equal(golden.failedPresented.success, false);
  assert.equal(golden.failedPresented.failureCode, 'EVIDENCE_UNAVAILABLE');
  assert.equal(golden.failedPresented.sampleMonoMs, undefined);
  assert.equal(golden.failedPresented.positionMs, undefined);
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
  assert.ok(golden.heartbeat.sampleMonoMs > golden.ackPresented.sampleMonoMs);
  const presentedAt = serverSample(golden.ackPresented.sampleMonoMs,
      golden.clockModel);
  const heartbeatAt = serverSample(golden.heartbeat.sampleMonoMs,
      golden.clockModel);
  assert.ok(golden.heartbeatAck.serverMonoMs >= heartbeatAt);
  assert.ok(Math.abs(predictedPosition(golden.snapshotReply, presentedAt)
      - golden.ackPresented.positionMs) <= 1);
  assert.ok(Math.abs(predictedPosition(golden.snapshotReply, heartbeatAt)
      - golden.heartbeat.positionMs) <= 1);
  assert.equal(predictedPosition(golden.snapshotReply,
      golden.heartbeatAck.serverMonoMs), golden.heartbeatAck.desiredPositionMs);
});

test('paused heartbeat may repeat the exact admitted visible frame', () => {
  assert.equal(golden.pausedHeartbeat.state, 'PAUSED');
  assert.equal(golden.repeatedPausedHeartbeat.state, 'PAUSED');
  assert.equal(golden.repeatedPausedHeartbeat.sampleMonoMs,
      golden.pausedHeartbeat.sampleMonoMs);
  assert.equal(golden.repeatedPausedHeartbeat.positionMs,
      golden.pausedHeartbeat.positionMs);
  assert.notEqual(golden.repeatedPausedHeartbeat.requestId,
      golden.pausedHeartbeat.requestId);
});

test('buffering gates one recovery comparison', () => {
  assert.equal(golden.bufferingHeartbeat.state, 'BUFFERING');
  assert.equal(golden.recoveredPlayingHeartbeat.state, 'PLAYING');
  assert.ok(golden.recoveredPlayingHeartbeat.sampleMonoMs
      > golden.bufferingHeartbeat.sampleMonoMs);
  assert.equal(golden.bufferingHeartbeat.positionMs,
      golden.heartbeat.positionMs);
});

test('growing media window is replaced as one ordered tuple', () => {
  const keys = ['durationMs', 'mediaGrowing', 'availableEndMs',
    'playableEndMs', 'liveEdgeDelayMs'];
  for (const key of keys) assert.ok(Object.hasOwn(golden.growingSnapshot, key));
  assert.ok(golden.grownSnapshot.serverMonoMs > golden.growingSnapshot.serverMonoMs);
  assert.ok(golden.grownSnapshot.availableEndMs
      > golden.growingSnapshot.availableEndMs);
  assert.equal(golden.grownSnapshot.playableEndMs,
      golden.grownSnapshot.availableEndMs - golden.grownSnapshot.liveEdgeDelayMs);
  assert.equal(golden.growingTemporaryEndHeartbeat.state, 'BUFFERING');
  assert.equal(golden.growingFinalSnapshot.mediaGrowing, false);
  assert.equal(golden.growingDrainedEosHeartbeat.state, 'STOPPED');
  assert.equal(golden.growingDrainedEosHeartbeat.positionMs,
      golden.growingFinalSnapshot.playableEndMs);
});

test('unnegotiated legacy playback does not receive Party wire extensions', () => {
  assert.equal(golden.legacyCompatibility.partyNegotiated, false);
  assert.equal(golden.legacyCompatibility.partyMessagesSent, 0);
  assert.equal(golden.legacyCompatibility.partyBindingCommandSent, false);
  assert.equal(golden.legacyCompatibility.ordinaryPlaybackPath, 'UNCHANGED');
});

test('browser-managed native HLS is rejected when it cannot attach the binding header', () => {
  assert.equal(golden.pwaNativeHlsPartyRejection.partyPrepare, true);
  assert.equal(golden.pwaNativeHlsPartyRejection.canAttachBindingHeader, false);
  assert.equal(golden.pwaNativeHlsPartyRejection.eligible, false);
  assert.equal(golden.pwaNativeHlsPartyRejection.failureCode,
      'UNSUPPORTED_PARTY_DELIVERY');
});

test('exact retry is stable and out-of-order directive identity fails', () => {
  assert.equal(golden.ackArmedInaccurateResponse.requestId,
      golden.ackArmedInaccurate.requestId);
  assert.equal(golden.ackArmed.directiveId,
      golden.ackArmedInaccurate.directiveId);
  assert.notEqual(golden.ackArmed.requestId,
      golden.ackArmedInaccurate.requestId);
  assert.ok(golden.ackArmed.sampleMonoMs
      > golden.ackArmedInaccurate.sampleMonoMs);
  assert.deepEqual(golden.ackArmedRetryResponse, golden.ackArmedResponse);
  assert.equal(golden.outOfOrderAckError.code, 'STALE_DIRECTIVE');
});

test('local Stop is a heartbeat state and changes host authority', () => {
  assert.equal(golden.localStop.type, 'HEARTBEAT');
  assert.equal(golden.localStop.state, 'STOPPED');
  assert.ok(golden.localStop.sampleMonoMs
      > golden.recoveredPlayingHeartbeat.sampleMonoMs);
  assert.equal(golden.localStop.positionMs,
      golden.recoveredPlayingHeartbeat.positionMs);
  assert.equal(golden.hostChanged.hostParticipantId, 'guest');
});

test('join code is returned only as an explicit host request reply', () => {
  assert.equal(golden.joinCodeReply.type, 'JOIN_CODE');
  assert.equal(golden.joinCodeReply.requestId, 'join-code-1');
  assert.equal(typeof golden.joinCodeReply.serverMonoMs, 'number');
  assert.equal(golden.joinCodeReply.joinCode, '042');
});
