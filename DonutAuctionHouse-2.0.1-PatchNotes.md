# DonutAuctionHouse v2.0.1

## Fixed

- **Item duplication when listing.** After a listing was saved, a failure in any later step (the cross-server
  sync, for example when Redis was briefly unreachable, the listing cache, the broadcast or the confirmation
  message) was treated as a failed listing: the seller got the item back while the listing stayed live and
  could still be bought. Now only a failed save returns the item and any listing fee. Once the listing is saved
  it stays the only copy, and a failed later step is logged instead.

No config, storage or API changes. Update by replacing the jar.
