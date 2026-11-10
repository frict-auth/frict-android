// Command signing-vectors prints device-signing test vectors made by
// frict-server's own code. scripts/gen-signing-vectors.sh copies it into a
// frict-server source tree, because it imports that module's internal
// packages.
package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"log"
	"net/http/httptest"
	"os"
	"time"

	"github.com/frict-auth/frict-server/internal/devicesig"
	"github.com/frict-auth/frict-server/internal/fakephone"
)

type vector struct {
	Name          string `json:"name"`
	UserID        string `json:"user_id"`
	UnixSeconds   int64  `json:"unix_seconds"`
	Path          string `json:"path"`
	Body          string `json:"body"`
	SignedBytes   string `json:"signed_bytes"`
	Authorization string `json:"authorization"`
}

func main() {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	check(err)
	pkcs8, err := x509.MarshalPKCS8PrivateKey(key)
	check(err)
	spki, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	check(err)

	cases := []vector{
		{Name: "enroll, newlines in body", UserID: "DUABCDEFGHIJKLMNOPQRSTUVWXYZ", UnixSeconds: 1762345678, Path: fakephone.EnrollPath, Body: "{\"a\":\n1}\n"},
		{Name: "pending, empty object", UserID: "DU234567234567234567234567AB", UnixSeconds: 1, Path: fakephone.PendingPath, Body: "{}"},
		{Name: "answer, 12-digit time", UserID: "DUZZZZZZZZZZZZZZZZZZZZZZZZZZ", UnixSeconds: 999999999999, Path: fakephone.AnswerPath, Body: `{"challenge_id":"AAAAAAAAAAAAAAAAAAAAAA","decision":"deny","reason":"not_me"}`},
		{Name: "fid, multibyte UTF-8", UserID: "DUQRSTUVWXYZ234567ABCDEFGHIJ", UnixSeconds: 1762345999, Path: fakephone.FIDPath, Body: `{"fid":"fé€𝄞"}`},
		{Name: "challenge", UserID: "DUABCDEFGHIJKLMNOPQRSTUVWXYZ", UnixSeconds: 1762345678, Path: fakephone.ChallengePath, Body: `{"challenge_id":"AAAAAAAAAAAAAAAAAAAAAA"}`},
	}
	for i := range cases {
		c := &cases[i]
		body := []byte(c.Body)
		c.SignedBytes = base64.StdEncoding.EncodeToString(devicesig.SignedBytes(c.UserID, c.UnixSeconds, c.Path, body))
		r, err := fakephone.SignedRequest("https://frict.test", c.Path, c.UserID, c.UnixSeconds, body, key)
		check(err)
		c.Authorization = r.Header.Get("Authorization")

		v := httptest.NewRequest("POST", c.Path, bytes.NewReader(body))
		v.Header.Set("Authorization", c.Authorization)
		_, err = devicesig.Verify(v, body, time.Unix(c.UnixSeconds, 0), func(string) (*ecdsa.PublicKey, error) { return &key.PublicKey, nil })
		check(err)
	}

	enc := json.NewEncoder(os.Stdout)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	check(enc.Encode(map[string]any{
		"frict_server_commit": os.Args[1],
		"private_key_pkcs8":   base64.StdEncoding.EncodeToString(pkcs8),
		"public_key_spki":     base64.StdEncoding.EncodeToString(spki),
		"vectors":             cases,
	}))
}

func check(err error) {
	if err != nil {
		log.Fatal(err)
	}
}
