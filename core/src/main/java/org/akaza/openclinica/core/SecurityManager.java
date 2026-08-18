package org.akaza.openclinica.core;

import java.security.NoSuchAlgorithmException;
import java.util.Random;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;

public class SecurityManager {

    private PasswordEncoder encoder;

    private AuthenticationProvider providers[];

    public String genPassword() {
        return genPassword(8);
    }

    public String genPassword(int howmany) {
        String ret = "";
        String core = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random rand = new Random();
        for (int i = 0; i < howmany; i++) {
            int thisOne = rand.nextInt(core.length());
            char thisOne2 = core.charAt(thisOne);
            ret += thisOne2;
        }
        return ret;
    }

    public String encrytPassword(String password, UserDetails userDetails) throws NoSuchAlgorithmException {
        return encoder.encode(password);
    }

    @Deprecated
    public boolean isPasswordValid(String encPass, String rawPass, UserDetails userDetails) {
        return encoder.matches(rawPass, encPass);
    }

    public boolean verifyPassword(String clearTextPassword, UserDetails userDetails) {
        Object principal = userDetails != null ? userDetails : "";
        Authentication authentication = new UsernamePasswordAuthenticationToken(principal, clearTextPassword);
        for (AuthenticationProvider p : providers) {
            try {
                Authentication result = p.authenticate(authentication);
                if (result != null && result.isAuthenticated()) {
                    return true;
                }
            } catch (AuthenticationException e) {
            }
        }
        return false;
    }

    public PasswordEncoder getEncoder() {
        return encoder;
    }

    public void setEncoder(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    public AuthenticationProvider[] getProviders() {
        return providers;
    }

    public void setProviders(AuthenticationProvider[] providers) {
        this.providers = providers;
    }
}
