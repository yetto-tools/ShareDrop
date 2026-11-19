//! Vectores fijos del protocolo. Los mismos valores están en la prueba de C# y deben dar igual
//! (así se comprueba que Rust, C# y el APK hablan el mismo protocolo).

use aes_gcm::aead::KeyInit;
use aes_gcm::Aes256Gcm;
use sharedrop_core::channel::{hmac, pin_secret, seal};

#[test]
fn print_vectors() {
    let key: Vec<u8> = (0u8..32).collect();
    let cipher = Aes256Gcm::new_from_slice(&key).unwrap();
    let body = seal(&cipher, 1, 5, 8, &[1, 2, 3]);
    let nc = [1u8; 16];
    let ns = [2u8; 16];
    let secret = pin_secret("123456");
    println!("PIN_SECRET={}", hex::encode(&secret));
    println!("PROOF_C={}", hex::encode(hmac(&secret, &[b"C", &nc, &ns])));
    println!("SESSION_KEY={}", hex::encode(hmac(&secret, &[b"key", &nc, &ns])));
    println!("FRAME_BODY={}", hex::encode(body));
}
