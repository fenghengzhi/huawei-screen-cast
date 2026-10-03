package com.local.huaweicast;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/** Explicit local interoperability probe. Never captures or transmits screen/audio content. */
public final class LelinkHandshakeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !args[0].matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            throw new IllegalArgumentException("Expected discovered IPv4, lelinkport, htv and atv");
        }
        int port = Integer.parseInt(args[1]);
        LelinkEndpoint endpoint = LelinkEndpoint.from("Local test receiver", InetAddress.getByName(args[0]), port,
                Map.of("lelinkport", ascii(args[1]), "htv", ascii(args[2]), "atv", ascii(args[3])));
        if (!endpoint.advertisesFreeNativePairing()) throw new IllegalArgumentException("Receiver does not advertise FREE pairing");
        try (LelinkControlClient control = LelinkControlClient.connect(endpoint, socket -> {});
             LelinkFreePairing pairing = new LelinkFreePairing()) {
            LelinkControlClient.Response info = control.readPlayerInfo();
            requireSuccess("PLAYER_INFO", info);
            if (!LelinkPlayerInfo.parse(info.body()).advertisesFreePairing()) {
                throw new IllegalStateException("Receiver requires another mode; no fallback attempted");
            }
            LelinkControlClient.Response setup = control.setup(pairing.initialRequest());
            requireSuccess("SETUP_M2", setup);
            LelinkControlClient.Response verify = control.verify(pairing.acceptSetupReturnVerify(setup.body()));
            requireSuccess("VERIFY_M4", verify);
            LelinkControlClient.Response finish = control.verify(pairing.acceptVerifyReturnFinish(verify.body()));
            requireSuccess("VERIFY_M6", finish);
            try (LelinkFreePairing.ControlSecrets secrets = pairing.acceptFinish(finish.body())) {
                byte[] key = secrets.key();
                byte[] nonce = secrets.nonce();
                try { control.enableEncryption(key, nonce); }
                finally { Arrays.fill(key, (byte) 0); Arrays.fill(nonce, (byte) 0); }
                for (int i = 1; i <= 3; i++) requireSuccess("ENCRYPTED_PLAYER_INFO_" + i, control.readPlayerInfo());
                System.out.println("FREE_CONTROL_VERIFIED: no media was requested");
            }
        }
    }

    private static void requireSuccess(String stage, LelinkControlClient.Response response) {
        System.out.println(stage + " status=" + response.status() + " bytes=" + response.body().length);
        if (response.status() != 200) throw new IllegalStateException("Receiver rejected " + stage);
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
}
