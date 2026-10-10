package com.elia.evoconti;

import android.content.Context;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

import it.evocore.client.EvoCoreClient;
import it.evocore.client.EvoCoreProtocol;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PersonalMemoryRecallInstrumentedTest {
    private static final String TAG = "EvoCoreRecallTest";

    @Test public void recallsExpectedConfirmedMemoryThroughRealClient() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String question = decode(arguments.getString("questionBase64", ""));
        String expected = decode(arguments.getString("expectedBase64", ""));
        assertTrue("questionBase64 is required", !question.isEmpty());
        assertTrue("expectedBase64 is required", !expected.isEmpty());

        Context context = ApplicationProvider.getApplicationContext();
        assertTrue("EvoCore host must be installed", EvoCoreClient.isInstalled(context));
        try (EvoCoreClient client = new EvoCoreClient(context, "evoconti", null)) {
            assertTrue("EvoCore connection failed", client.connect());
            EvoCoreClient.Result result = client.askBlocking(
                    "{\"schema\":\"evoconti.dialogue.v1\",\"redditoNetto\":0,"
                            + "\"saldoMensile\":0,\"debitiPct\":0,\"fondoEmergenzaMesi\":0,"
                            + "\"saluteBudget\":70}",
                    question, "custodian", 120_000L);
            assertTrue(result.error, result.ok);
            JSONObject answer = json(result.data.getString(EvoCoreProtocol.KEY_ANSWER_JSON, "{}"));
            String message = answer.optString("messaggioBreve", "");
            Log.i(TAG, "Recall answer: " + message);
            assertTrue("Expected confirmed memory was not recalled: " + message,
                    message.toLowerCase().contains(expected.toLowerCase()));
        }
    }

    private static String decode(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) return "";
        return new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
    }

    private static JSONObject json(String value) {
        try {
            return new JSONObject(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
