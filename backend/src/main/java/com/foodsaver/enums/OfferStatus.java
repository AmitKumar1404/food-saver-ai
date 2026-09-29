package com.foodsaver.enums;

/**
 * Marketplace lifecycle states for an Offer.
 *
 * <p>These values do not represent food-safety or eligibility decisions.
 */
public enum OfferStatus {
	/** Currently available in the marketplace. This does not mean the food is safe. */
	ACTIVE,

	/** Marketplace availability has ended. This does not mean the food is unsafe. */
	EXPIRED,

	/** Reserved for a future allocation workflow that exhausts the offered quantity. */
	SOLD_OUT,

	/** Reserved for a future authorized lifecycle close operation. */
	CLOSED
}
