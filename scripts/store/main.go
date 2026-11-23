// Command store reads and writes frict-server's database for scripts/e2e.sh,
// which copies it into a frict-server source tree, because it imports that
// module's internal/store. It prints IDs as the device protocol encodes them.
//
// Usage:
//
//	store fid USER_ID          the device's stored FID
//	store setfid USER_ID FID   replaces the stored FID
//	store challenge USER_ID    the oldest pending challenge's ID
//
// Environment: E2E_DB (the database file).
package main

import (
	"context"
	"encoding/base64"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/frict-auth/frict-server/internal/store"
)

func main() {
	log.SetFlags(0)
	path := os.Getenv("E2E_DB")
	if path == "" {
		log.Fatal("E2E_DB is not set")
	}
	s, err := store.Open(path)
	check(err)
	defer s.Close()
	ctx := context.Background()
	args := os.Args[1:]
	switch {
	case len(args) == 2 && args[0] == "fid":
		d, err := s.DeviceOf(ctx, args[1])
		check(err)
		fmt.Println(d.FID)
	case len(args) == 3 && args[0] == "setfid":
		check(s.SetFID(ctx, args[1], args[2]))
	case len(args) == 2 && args[0] == "challenge":
		cs, err := s.PendingChallenges(ctx, args[1], time.Now())
		check(err)
		if len(cs) == 0 {
			log.Fatalf("no pending challenge for %s", args[1])
		}
		fmt.Println(base64.RawURLEncoding.EncodeToString(cs[0].ID))
	default:
		log.Fatal("usage: store fid USER_ID | store setfid USER_ID FID | store challenge USER_ID")
	}
}

func check(err error) {
	if err != nil {
		log.Fatal(err)
	}
}
