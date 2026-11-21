// Command duo makes the Duo-signed calls Authentik's Duo stages make to
// frict-server: /auth/v2/enroll, which prints the activation link,
// /auth/v2/enroll_status, which prints the status, and the synchronous
// /auth/v2/auth, which prints "<result> <status>: <status_msg>" once the
// device answers or the challenge expires. scripts/e2e.sh copies it into a
// frict-server source tree, because it imports that module's internal/duosig.
//
// With CAMERA_PNG, enroll also writes the activation's QR code (the server's
// activation_barcode) as the emulator's back camera image; qr writes a QR code
// of TEXT the same way.
//
// Usage:
//
//	duo enroll USERNAME [CAMERA_PNG]
//	duo status USER_ID LINK
//	duo auth USER_ID USERNAME
//	duo qr TEXT CAMERA_PNG
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
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	"image/draw"
	"image/png"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/boombuler/barcode"
	"github.com/boombuler/barcode/qr"
	"github.com/frict-auth/frict-server/internal/duosig"
)

func main() {
	log.SetFlags(0)
	args := os.Args[1:]
	switch {
	case (len(args) == 2 || len(args) == 3) && args[0] == "enroll":
		var r struct {
			ActivationCode    string `json:"activation_code"`
			ActivationBarcode string `json:"activation_barcode"`
		}
		call("/auth/v2/enroll", map[string]string{"username": args[1]}, &r)
		if len(args) == 3 {
			png64, ok := strings.CutPrefix(r.ActivationBarcode, "data:image/png;base64,")
			if !ok {
				log.Fatalf("activation_barcode is not a PNG data URL: %.40q", r.ActivationBarcode)
			}
			raw, err := base64.StdEncoding.DecodeString(png64)
			check(err)
			code, err := png.Decode(bytes.NewReader(raw))
			check(err)
			writeCameraImage(args[2], code)
		}
		fmt.Println(r.ActivationCode)
	case len(args) == 3 && args[0] == "qr":
		code, err := qr.Encode(args[1], qr.M, qr.Auto)
		check(err)
		code, err = barcode.Scale(code, code.Bounds().Dx()*6, code.Bounds().Dy()*6)
		check(err)
		writeCameraImage(args[2], withQuietZone(code))
	case len(args) == 3 && args[0] == "status":
		var status string
		call("/auth/v2/enroll_status", map[string]string{"user_id": args[1], "activation_code": args[2]}, &status)
		fmt.Println(status)
	case len(args) == 3 && args[0] == "auth":
		// What Authentik's Duo validation stage sends (authenticator_validate/challenge.py:276-290).
		var r struct {
			Result    string `json:"result"`
			Status    string `json:"status"`
			StatusMsg string `json:"status_msg"`
		}
		call("/auth/v2/auth", map[string]string{
			"user_id": args[1], "factor": "auto", "async": "0", "device": "auto",
			"ipaddr": "203.0.113.7", "type": "Frict e2e login", "display_username": args[2],
			"pushinfo": "Domain=e2e.frict.test&Application=Frict+Android+e2e",
		}, &r)
		fmt.Printf("%s %s: %s\n", r.Result, r.Status, r.StatusMsg)
	default:
		log.Fatal("usage: duo enroll USERNAME [CAMERA_PNG] | duo status USER_ID LINK | duo auth USER_ID USERNAME | duo qr TEXT CAMERA_PNG")
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
		Timeout: 150 * time.Second, // /auth holds the call for up to 120 s
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

// writeCameraImage writes code where the emulator's back camera (hw.camera.back
// orientation 90, -camera-back imagefile:) shows all of it. The emulator scales
// the image so its height fills the frame's 640 px width, shows only the left
// three quarters of the image's height in width, and turns it; so code goes in
// the middle of that part, on white.
func writeCameraImage(path string, code image.Image) {
	side := code.Bounds().Dx()
	img := image.NewGray(image.Rect(0, 0, side*8/3, side*2))
	draw.Draw(img, img.Bounds(), image.White, image.Point{}, draw.Src)
	at := image.Pt(side/4, side/2)
	draw.Draw(img, code.Bounds().Sub(code.Bounds().Min).Add(at), code, code.Bounds().Min, draw.Src)
	f, err := os.Create(path)
	check(err)
	check(png.Encode(f, img))
	check(f.Close())
}

// withQuietZone adds the 4-module (24 px) white border the server's QR codes have.
func withQuietZone(code image.Image) image.Image {
	const quiet = 24
	b := code.Bounds()
	img := image.NewGray(image.Rect(0, 0, b.Dx()+2*quiet, b.Dy()+2*quiet))
	draw.Draw(img, img.Bounds(), image.White, image.Point{}, draw.Src)
	draw.Draw(img, b.Sub(b.Min).Add(image.Pt(quiet, quiet)), code, b.Min, draw.Src)
	return img
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
