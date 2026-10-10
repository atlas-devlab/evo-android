package it.evocore.client;

import android.os.Bundle;
import it.evocore.client.CancelRequest;
import it.evocore.client.CoreRequest;
import it.evocore.client.IEvoCoreCallback;
import it.evocore.client.ToolResult;

interface IEvoCoreService {
    Bundle handshake(in Bundle request);
    void ask(in Bundle request, IEvoCoreCallback callback);
    Bundle remember(in Bundle request);
    Bundle listMemories(in Bundle request);
    Bundle updateMemory(in Bundle request);
    Bundle getStatus(in Bundle request);
    void submit(in Bundle auth, in CoreRequest request, IEvoCoreCallback callback);
    void cancel(in Bundle auth, in CancelRequest request);
    void submitToolResult(in Bundle auth, in ToolResult result);
    void closeSession(in Bundle auth);
}
