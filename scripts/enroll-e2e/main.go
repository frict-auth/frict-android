// Command enroll-e2e makes the Duo-signed calls Authentik's Duo setup stage
// makes to frict-server: /auth/v2/enroll, which prints the activation link,
// and /auth/v2/enroll_status, which prints the status. scripts/enroll-e2e.sh
// copies it into a frict-server source tree, because it imports that module's
// internal/duosig.
//
// Usage:
//
//	enroll-e2e enroll USERNAME
//	enroll-e2e status USER_ID LINK
//
// Environment: E2E_HOST (API hostname, as signed), E2E_ADDR (where to dial
// it), E2E_CA (CA certificate PEM), E2E_IKEY (client ID) and E2E_SKEY_FILE
// (client secret file).
package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/frict-auth/frict-server/internal/duosig"
)

func main() {
	log.SetFlags(0)
	args := os.Args[1:]
	switch {
	case len(args) == 2 && args[0] == "enroll":
		var r struct {
			ActivationCode string `json:"activation_code"`
		}
		call("/auth/v2/enroll", map[string]string{"username": args[1]}, &r)
		fmt.Println(r.ActivationCode)
	case len(args) == 3 && args[0] == "status":
		var status string
		call("/auth/v2/enroll_status", map[string]string{"user_id": args[1], "activation_code": args[2]}, &status)
		fmt.Println(status)
	default:
		log.Fatal("usage: enroll-e2e enroll USERNAME | enroll-e2e status USER_ID LINK")
	}
}

func call(path string, params map[string]string, response any) {
	host, addr := env("E2E_HOST"), env("E2E_ADDR")
	secret, err := os.ReadFile(env("E2E_SKEY_FILE"))
	check(err)
	caPEM, err := os.ReadFile(env("E2E_CA"))
	check(err)
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(caPEM) {
		log.Fatalf("no certificates in %s", env("E2E_CA"))
	}
	var d net.Dialer
	client := &http.Client{
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{RootCAs: roots},
			DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
				return d.DialContext(ctx, network, addr)
			},
		},
	}

	body, err := json.Marshal(params)
	check(err)
	req, err := http.NewRequest(http.MethodPost, "https://"+host+path, bytes.NewReader(body))
	check(err)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Date", time.Now().UTC().Format(time.RFC1123Z))
	check(duosig.Sign(req, body, env("E2E_IKEY"), strings.TrimSpace(string(secret)), host))
	resp, err := client.Do(req)
	check(err)
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	check(err)
	if resp.StatusCode != http.StatusOK {
		log.Fatalf("%s: HTTP %d %s", path, resp.StatusCode, raw)
	}
	var out struct {
		Stat     string          `json:"stat"`
		Response json.RawMessage `json:"response"`
	}
	check(json.Unmarshal(raw, &out))
	if out.Stat != "OK" {
		log.Fatalf("%s: %s", path, raw)
	}
	check(json.Unmarshal(out.Response, response))
}

func env(name string) string {
	v := os.Getenv(name)
	if v == "" {
		log.Fatalf("%s is not set", name)
	}
	return v
}

func check(err error) {
	if err != nil {
		log.Fatal(err)
	}
}
