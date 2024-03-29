package ar.edu.itba.pod.client;

import ar.edu.itba.pod.PartyVoteHandler;
import ar.edu.itba.pod.models.Vote;

public class PartyVoteHandlerImpl implements PartyVoteHandler {
    @Override
    public void onPartyVote(Vote vote) {
        System.out.format("New vote for %s on polling place %d\n", vote.getFptpVote().toString(), vote.getTable());
    }
}
