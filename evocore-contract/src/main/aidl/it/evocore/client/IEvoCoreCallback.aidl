package it.evocore.client;

import android.os.Bundle;
import it.evocore.client.CoreEvent;

oneway interface IEvoCoreCallback {
    void onResult(in Bundle response);
    void onError(int code, String message);
    void onEvent(in CoreEvent event);
}
