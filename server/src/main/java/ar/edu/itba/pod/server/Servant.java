package ar.edu.itba.pod.server;

import ar.edu.itba.pod.*;
import ar.edu.itba.pod.comparators.DoubleComparator;
import ar.edu.itba.pod.exceptions.InsufficientWinnersException;
import ar.edu.itba.pod.exceptions.InvalidElectionStateException;
import ar.edu.itba.pod.exceptions.NoVotesRegisteredException;
import ar.edu.itba.pod.models.*;
import ar.edu.itba.pod.server.models.NationalElection;
import ar.edu.itba.pod.server.models.Round;
import ar.edu.itba.pod.server.models.StateElection;
import ar.edu.itba.pod.server.models.Table;
import org.apache.commons.lang3.tuple.MutablePair;

import java.rmi.RemoteException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class Servant implements AuditService, ManagementService, VoteService, QueryService {
    private static final int NUMBER_OF_THREADS = 4;
    private static final ExecutorService executor = Executors.newFixedThreadPool(NUMBER_OF_THREADS);

    private final Map<Party, Map<Integer, List<PartyVoteHandler>>> auditHandlers = new HashMap<>();
    private final HashMap<Integer, Table> tables = new HashMap<>();
    private final StateElection stateElection = new StateElection();
    private final NationalElection nationalElection = new NationalElection();

    /**
     * Variable to hold the state of the election
     */
    private ElectionState electionState = ElectionState.PENDING;

    private final String STATE_LOCK = "ELECTION_STATE_LOCK";

    // Will compare first with percentage and then the party
    private final DoubleComparator doubleComparator = new DoubleComparator();

    //////////////////////////////////////////////////////////////////////////////////////////
    //                                      AUDIT METHODS
    //////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void registerAuditOfficer(Party party, int table, PartyVoteHandler handler) throws RemoteException, InvalidElectionStateException {
        synchronized (this.STATE_LOCK) {
            // If election is still pending, it can be registered
            if (this.electionState == ElectionState.PENDING) {
                // Saving the vote handler to notify when new votes on a table for a certain party happen
                synchronized (auditHandlers) {
                    auditHandlers.computeIfAbsent(party, p -> new HashMap<>())
                            .computeIfAbsent(table, t-> new ArrayList<>())
                            .add(handler);
                }
            } else {
                throw new InvalidElectionStateException("Elections in progress or closed. Can no longer register an audit officer");
            }
        }
    }

    private void notifyPartyVote(Vote vote) throws RemoteException {
        Party party = vote.getFptpVote();
        Integer table = vote.getTable();

        synchronized (this.auditHandlers) {
            if (this.auditHandlers.containsKey(party) && this.auditHandlers.get(party).containsKey(table)) {
                for (PartyVoteHandler handler: this.auditHandlers.get(party).get(table)) {
                    handler.onPartyVote(vote);
                }
            }
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////
    //                                  MANAGEMENT METHODS
    //////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void openElection() throws RemoteException, InvalidElectionStateException {
        synchronized (this.STATE_LOCK){
            if (this.electionState != ElectionState.PENDING){
                throw new InvalidElectionStateException("Elections have already started/finished");
            }
            this.electionState = ElectionState.OPEN;
        }
    }

    @Override
    public void closeElection() throws RemoteException, InvalidElectionStateException {
        synchronized (this.STATE_LOCK){
            if (this.electionState != ElectionState.OPEN){
                throw new InvalidElectionStateException("Elections haven't started or have already finished");
            }
            this.nationalElection.computeNationalElectionResults();
            this.stateElection.computeStateElectionResults();
            this.electionState = ElectionState.CLOSED;
        }
    }

    @Override
    public ElectionState getElectionState() throws RemoteException {
        synchronized (this.STATE_LOCK){
            return this.electionState;
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////
    //                                      VOTE METHODS
    //////////////////////////////////////////////////////////////////////////////////////////
    
    public void emitVote(Vote vote) throws RemoteException, ExecutionException, InterruptedException, InvalidElectionStateException {
        // Synchronize the access to the election state
        synchronized (this.STATE_LOCK) {
            if (this.electionState != ElectionState.OPEN) {
                throw new InvalidElectionStateException("Elections haven't started or have already finished");
            }

            // Synchronize access to see if the key exists, perform the emission out of synchronized block
            synchronized (this.tables) {
                if (!this.tables.containsKey(vote.getTable())) {
                    this.tables.put(vote.getTable(), new Table(vote.getTable(), vote.getProvince()));
                }
            }

            // Emit the vote for the table
            this.tables.get(vote.getTable()).emitVote(vote.getFptpVote());

            // Processing the SPAV vote for the state election
            this.stateElection.emitVote(vote.getProvince(), vote.getSpavVote());

            // Processing the STAR vote for the national election
            this.nationalElection.emitVote(vote.getStarVote());
        }
        // Creating the runnable task
        Runnable notify = () -> {
            try {
                // Notify the vote
                this.notifyPartyVote(vote);
            } catch (RemoteException e) {
                // Notification will no succeed
            }
        };
        executor.submit(notify);
    }

    //////////////////////////////////////////////////////////////////////////////////////////
    //                                      QUERY METHODS
    //////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public ElectionResults getNationalResults() throws RemoteException, InvalidElectionStateException, NoVotesRegisteredException {
        ElectionState electionState;
        // In order to avoid locking the whole if blocks, I pass the value of the election to a local variable
        synchronized (this.STATE_LOCK) {
            electionState = ElectionState.fromValue(this.electionState.name());
        }

        if(electionState == ElectionState.OPEN) {
            return this.getAllTableResults(electionState);

        } else if(electionState == ElectionState.CLOSED) {
            Party winner = this.nationalElection.getNationalElectionWinner();
            if (winner == null) {
                throw new NoVotesRegisteredException();
            }
            return new NationalElectionsResult(
                    this.nationalElection.getSortedScoringRoundResults(),
                    this.nationalElection.getSortedAutomaticRunoffResults(),
                    winner
            );
        }

        // Elections have not began
        throw new InvalidElectionStateException("Elections PENDING. Can not request national results");
    }

    @Override
    public ElectionResults getProvinceResults(Province province) throws RemoteException, InvalidElectionStateException, NoVotesRegisteredException, InsufficientWinnersException {
        ElectionState electionState;

        // In order to avoid locking the whole if blocks, I pass the value of the election to a local variable
        synchronized (this.STATE_LOCK) {
            electionState = ElectionState.fromValue(this.electionState.name());
        }

        if(electionState == ElectionState.OPEN) {
            return this.getProvinceTableResults(province, electionState);
        }
        else if(electionState == ElectionState.CLOSED){
            if(this.stateElection.getFirstRound(province).size() == 0)
                throw new NoVotesRegisteredException();

            if(this.stateElection.getWinners(province).length != Round.values().length)
                throw new InsufficientWinnersException();

            return new StateElectionsResult(province,
                    this.stateElection.getFirstRound(province),
                    this.stateElection.getSecondRound(province),
                    this.stateElection.getThirdRound(province),
                    this.stateElection.getWinners(province));
        }

        throw new InvalidElectionStateException("Elections PENDING. Can not request state results");
    }

    @Override
    public ElectionResults getTableResults(Integer tableID) throws RemoteException, InvalidElectionStateException, NoVotesRegisteredException {
        ElectionState electionState;

        // In order to avoid locking the whole if blocks, I pass the value of the election to a local variable
        synchronized (this.STATE_LOCK) {
            electionState = ElectionState.fromValue(this.electionState.name());
        }

        if(electionState != ElectionState.PENDING){
            synchronized (this.tables) {
                if (!this.tables.containsKey(tableID)) {
                    throw new IllegalArgumentException("Table with id " + tableID + " does not exist.");
                }
            }

            return new FPTPResult(tables.get(tableID).getResultsFromTable(), electionState);
        }
        throw new InvalidElectionStateException("Elections PENDING. Can not request FPTP results");
    }

    // Will only be called when getNationalResults is called and elections are still open
    private ElectionResults getAllTableResults(ElectionState electionState) throws RemoteException, InvalidElectionStateException, NoVotesRegisteredException {
        Map<Party, Long> fptpVotes;
        synchronized (this.tables) {
            fptpVotes = this.tables.values().stream()
                    .flatMap(t -> t.getVotes().entrySet().stream())
                    .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.summingLong(e -> e.getValue().get())));
        }

        return newElectionResults(fptpVotes, electionState);
    }

    // Will only be called when getProvinceResults is called and elections are still open
    private ElectionResults getProvinceTableResults(Province province, ElectionState electionState) throws RemoteException, InvalidElectionStateException, NoVotesRegisteredException {
        Map<Party, Long> fptpVotes;
        synchronized (this.tables) {
            fptpVotes = this.tables.values().stream()
                    .filter(t -> t.getProvince().equals(province))
                    .flatMap(t -> t.getVotes().entrySet().stream())
                    .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.summingLong(e -> e.getValue().get())));
        }

        return newElectionResults(fptpVotes, electionState);
    }

    private ElectionResults newElectionResults(Map<Party, Long> fptpVotes, ElectionState electionState) throws NoVotesRegisteredException {
        boolean noVotes = fptpVotes.entrySet().stream().allMatch(e -> e.getValue() == 0L);
        // Error if there are no votes
        if(noVotes) {
            throw new NoVotesRegisteredException();
        }

        double totalVotes = (double) fptpVotes.values().stream().reduce(0L, Long::sum);

        TreeSet<MutablePair<Party, Double>> fptpResult = new TreeSet<>(this.doubleComparator);
        fptpVotes.forEach((key, value) -> fptpResult.add(new MutablePair<>(key, (((double) value / totalVotes)) * 100.0)));

        return new FPTPResult(fptpResult, electionState);
    }
}
