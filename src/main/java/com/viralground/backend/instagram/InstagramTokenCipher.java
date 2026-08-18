package com.viralground.backend.instagram;

/** Meta access token을 영속화하기 전/후 암복호화하는 포트. */
public interface InstagramTokenCipher {
    String encrypt(String plaintext);
    String decrypt(String ciphertext);
}
