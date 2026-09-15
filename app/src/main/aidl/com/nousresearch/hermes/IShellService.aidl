package com.nousresearch.hermes;

interface IShellService {
    String exec(String command);

    /**
     * Reserved method Shizuku calls to dispose of the user service. Required for the user
     * service contract -- without it shizuku_server cannot tear the instance down.
     */
    void destroy();
}
