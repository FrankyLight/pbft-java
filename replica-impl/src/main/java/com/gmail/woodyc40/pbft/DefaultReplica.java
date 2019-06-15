package com.gmail.woodyc40.pbft;

import com.gmail.woodyc40.pbft.message.*;

import java.util.concurrent.atomic.AtomicLong;

// POLICY: Signatures ignored
// POLICY: Digests ignored
public abstract class DefaultReplica<O, R, T> implements Replica<O, R> {
    private static final byte[] EMPTY_DIGEST = new byte[0];

    private final int replicaId;
    private final MessageLog log;
    private final Codec<T> codec;
    private final Transport<T> transport;

    private final AtomicLong seqCounter = new AtomicLong();

    public DefaultReplica(int replicaId, MessageLog log, Codec<T> codec, Transport<T> transport) {
        this.replicaId = replicaId;
        this.log = log;
        this.codec = codec;
        this.transport = transport;
    }

    @Override
    public int replicaId() {
        return this.replicaId;
    }

    @Override
    public MessageLog log() {
        return this.log;
    }

    @Override
    public void recvRequest(Request<O> request) {
        int primaryId = this.getPrimaryId();

        // We are not the primary replica, redirect
        if (this.replicaId != primaryId) {
            this.sendRequest(primaryId, request);
            return;
        }

        if (this.log.shouldBuffer()) {
            this.log.buffer(request);
            return;
        }

        PrePrepare<O> message = new PrePrepareImpl<>(
                this.transport.viewNumber(),
                this.seqCounter.getAndIncrement(),
                EMPTY_DIGEST,
                request);
        this.sendPrePrepare(message);
    }

    @Override
    public void sendRequest(int replicaId, Request<O> request) {
        T encodedPrePrepare = this.codec.encodeRequest(request);
        this.transport.redirectRequest(replicaId, encodedPrePrepare);
    }

    @Override
    public void sendPrePrepare(PrePrepare<O> prePrepare) {
        T encodedPrePrepare = this.codec.encodePrePrepare(prePrepare);
        this.transport.multicastPrePrepare(encodedPrePrepare);
    }

    @Override
    public void recvPrePrepare(PrePrepare<O> prePrepare) {
        int viewNumber = prePrepare.viewNumber();
        long seqNumber = prePrepare.seqNumber();
        if (this.log.exists(viewNumber, seqNumber)) {
            return;
        }

        if (!this.log.isBetweenWaterMarks(seqNumber)) {
            return;
        }

        int currentViewNumber = this.transport.viewNumber();
        if (currentViewNumber != viewNumber) {
            return;
        }

        this.log.add(prePrepare);

        Prepare prepare = new PrepareImpl(
                currentViewNumber,
                seqNumber,
                prePrepare.digest(),
                this.replicaId);
        this.log.add(prepare);
        this.sendPrepare(prepare);
    }

    @Override
    public void sendPrepare(Prepare prepare) {
        T encodedPrepare = this.codec.encodePrepare(prepare);
        this.transport.multicastPrePrepare(encodedPrepare);
    }

    @Override
    public void recvPrepare(Prepare prepare) {
        int currentViewNumber = this.transport.viewNumber();
        int viewNumber = prepare.viewNumber();
        if (currentViewNumber != viewNumber) {
            return;
        }

        long seqNumber = prepare.seqNumber();
        if (!this.log.isBetweenWaterMarks(seqNumber)) {
            return;
        }

        this.log.add(prepare);

        if (this.log.isPrepared(prepare)) {
            Commit commit = new CommitImpl(
                    viewNumber,
                    seqNumber,
                    EMPTY_DIGEST,
                    this.replicaId);
            this.sendCommit(commit);
        }
    }

    @Override
    public void sendCommit(Commit commit) {
        T encodedCommit = this.codec.encodeCommit(commit);
        this.transport.multicastPrePrepare(encodedCommit);
    }

    @Override
    public void recvCommit(Commit commit) {
        int currentViewNumber = this.transport.viewNumber();
        int viewNumber = commit.viewNumber();
        if (currentViewNumber != viewNumber) {
            return;
        }

        long seqNumber = commit.seqNumber();
        if (!this.log.isBetweenWaterMarks(seqNumber)) {
            return;
        }

        this.log.add(commit);

        if (this.log.isCommittedLocal(commit)) {
            Ticket<O> ticket = this.log.getTicket(seqNumber);
            if (ticket == null) {
                throw new IllegalStateException();
            }

            Request<O> request = ticket.request();
            R result = this.compute(request.operation());

            String clientId = request.clientId();
            Reply<R> reply = new ReplyImpl<>(
                    viewNumber,
                    request.timestamp(),
                    clientId,
                    this.replicaId,
                    result);
            this.sendReply(clientId, reply);
        }
    }

    @Override
    public void sendReply(String clientId, Reply<R> reply) {
        T encodedReply = this.codec.encodeReply(reply);
        this.transport.sendReply(clientId, encodedReply);
    }

    @Override
    public Codec<T> codec() {
        return this.codec;
    }

    @Override
    public Transport<T> transport() {
        return this.transport;
    }

    private int getPrimaryId() {
        return this.transport.viewNumber() % this.transport.countKnownReplicas();
    }
}