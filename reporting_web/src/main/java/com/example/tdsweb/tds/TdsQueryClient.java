package com.example.tdsweb.tds;

import com.example.tdsweb.domain.ClientCandidate;
import com.example.tdsweb.domain.ClientQueryResult;
import java.util.List;
import java.util.Optional;

public interface TdsQueryClient {
    List<ClientCandidate> searchClients(String query);

    ClientQueryResult queryClient(String clientId, Optional<Integer> tradeDate);
}
