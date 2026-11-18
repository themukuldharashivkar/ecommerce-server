package com.bazaarly.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bazaarly.modal.Product;
import com.bazaarly.payment.PaymentGateway;
import com.bazaarly.support.IntegrationTest;
import com.razorpay.RazorpayException;

/** Cancelling a paid order refunds it through Razorpay; the admin product edit page can change every field. */
class RefundAndProductUpdateIntegrationTest extends IntegrationTest {

	@Autowired JdbcTemplate jdbc;
	@Autowired org.springframework.transaction.support.TransactionTemplate transaction;
	@Autowired com.bazaarly.repository.ProductRepository productRepository;

	private long placeOrder(String token, Product product, int quantity) throws Exception {
		mvc.perform(put("/api/cart/add").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("productId", product.getId(), "size", "M", "quantity", quantity))))
				.andExpect(status().isAccepted());
		return readJson(mvc.perform(post("/api/orders/").header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"firstName\":\"Asha\",\"lastName\":\"Rao\",\"streetAddress\":\"12 MG Road\",\"city\":\"Pune\",\"state\":\"MH\",\"zipCode\":\"411001\",\"mobile\":\"9876543210\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();
	}

	private void pay(String token, long orderId, long amountInPaise) throws Exception {
		when(paymentGateway.createPaymentLink(any())).thenReturn(
				new PaymentGateway.CreatedPaymentLink("plink_" + orderId, "https://rzp.io/x", "order_rzp_" + orderId));
		mvc.perform(post("/api/payments/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isAccepted());
		when(paymentGateway.fetchPayment("pay_" + orderId))
				.thenReturn(new PaymentGateway.ProviderPayment("captured", "order_rzp_" + orderId, amountInPaise));
		mvc.perform(get("/api/payments").param("payment_id", "pay_" + orderId).param("order_id", String.valueOf(orderId))
				.header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
	}

	private String col(String column, long orderId) {
		return jdbc.queryForObject("SELECT " + column + " FROM orders WHERE id = ?", String.class, orderId);
	}

	private int stock(Product product) {
		return jdbc.queryForObject("SELECT quantity FROM product WHERE id = ?", Integer.class, product.getId());
	}

	// ---------- refunds ----------

	@Test
	void cancellingAPaidOrderRefundsItInFullAndReleasesTheStock() throws Exception {
		Product product = createProduct("refund_tee", 1000, 800, "Red", "M");
		String token = newUserToken();
		long orderId = placeOrder(token, product, 2);
		pay(token, orderId, 160000);
		when(paymentGateway.refundInFull("pay_" + orderId, 160000, "Order " + orderId + " cancelled"))
				.thenReturn(new PaymentGateway.Refund("rfnd_" + orderId, "processed"));
		String admin = adminToken();

		mvc.perform(put("/api/admin/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.orderStatus").value("CANCELLED"))
				.andExpect(jsonPath("$.paymentDetails.status").value("REFUNDED"))
				.andExpect(jsonPath("$.paymentDetails.razorpayRefundId").value("rfnd_" + orderId));
		mvc.perform(put("/api/admin/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isAccepted());

		verify(paymentGateway, times(1)).refundInFull(eq("pay_" + orderId), anyLong(), anyString());
		assertThat(stock(product)).isEqualTo(10);
	}

	@Test
	void failedRefundLeavesThePaidOrderUntouched() throws Exception {
		Product product = createProduct("refund_fail", 1000, 800, "Red", "M");
		String token = newUserToken();
		long orderId = placeOrder(token, product, 1);
		pay(token, orderId, 80000);
		when(paymentGateway.refundInFull(eq("pay_" + orderId), anyLong(), anyString()))
				.thenThrow(new RazorpayException("BAD_REQUEST_ERROR"));

		mvc.perform(put("/api/admin/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error").value("The payment provider could not complete the request. Please try again."));

		assertThat(col("order_status", orderId)).isEqualTo("PLACED");
		assertThat(col("status", orderId)).isEqualTo("COMPLETED");
		assertThat(stock(product)).isEqualTo(9); // still reserved for the paid order
	}

	@Test
	void cancellingAnUnpaidOrderNeedsNoRefund() throws Exception {
		Product product = createProduct("refund_unpaid", 1000, 800, "Red", "M");
		long orderId = placeOrder(newUserToken(), product, 1);

		mvc.perform(put("/api/admin/orders/" + orderId + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.paymentDetails.status").value("PENDING"));

		verify(paymentGateway, never()).refundInFull(anyString(), anyLong(), anyString());
		assertThat(stock(product)).isEqualTo(10);
	}

	@Test
	void latePaymentWhoseRefundFailsAsksTheShopperToContactSupport() throws Exception {
		Product product = createProduct("refund_late_fail", 1000, 800, "Red", "M");
		String token = newUserToken();
		long orderId = placeOrder(token, product, 1);
		when(paymentGateway.createPaymentLink(any())).thenReturn(
				new PaymentGateway.CreatedPaymentLink("plink_" + orderId, "https://rzp.io/x", "order_rzp_" + orderId));
		mvc.perform(post("/api/payments/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isAccepted());
		jdbc.update("UPDATE orders SET order_status = 'CANCELLED' WHERE id = ?", orderId); // expired meanwhile
		when(paymentGateway.fetchPayment("pay_x" + orderId))
				.thenReturn(new PaymentGateway.ProviderPayment("captured", "order_rzp_" + orderId, 80000));
		when(paymentGateway.refundInFull(eq("pay_x" + orderId), anyLong(), anyString())).thenThrow(new RazorpayException("down"));

		mvc.perform(get("/api/payments").param("payment_id", "pay_x" + orderId).param("order_id", String.valueOf(orderId))
						.header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("This order expired before the payment completed and the automatic refund failed. Please contact support."));
	}

	// ---------- admin product update ----------

	@Test
	void adminCanEditEveryFieldOnTheUpdatePage() throws Exception {
		Product product = createProduct("update_all", 1000, 800, "Red", "M");
		String body = """
				{"title":"Renamed tee","description":"New description","brand":"NewBrand","color":"Green",
				 "imageUrl":"https://example.com/new.jpg","price":1200,"discountedPrice":900,"discountPersent":25,"quantity":7}""";

		mvc.perform(put("/api/admin/products/" + product.getId() + "/update").header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Renamed tee"))
				.andExpect(jsonPath("$.brand").value("NewBrand"))
				.andExpect(jsonPath("$.color").value("Green"))
				.andExpect(jsonPath("$.price").value(1200))
				.andExpect(jsonPath("$.discountedPrice").value(900))
				.andExpect(jsonPath("$.quantity").value(7));
		mvc.perform(get("/api/products/id/" + product.getId())).andExpect(jsonPath("$.title").value("Renamed tee"));
	}

	@Test
	void discountedPriceIsCheckedAgainstTheStoredPrice() throws Exception {
		Product product = createProduct("update_price", 1000, 800, "Red", "M");

		mvc.perform(put("/api/admin/products/" + product.getId() + "/update").header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
						.contentType(MediaType.APPLICATION_JSON).content("{\"discountedPrice\":1500}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Discounted price cannot be higher than the price"));
		mvc.perform(get("/api/products/id/" + product.getId())).andExpect(jsonPath("$.discountedPrice").value(800));
	}

	@Test
	void editingAProductDoesNotOverwriteStockReservedMeanwhile() throws Exception {
		Product product = createProduct("update_stock", 1000, 800, "Red", "M");

		// the race, made deterministic: the admin's edit loads the product, then an order reserves stock
		// (a SQL update, like StockService), then the edit is saved
		transaction.executeWithoutResult(status -> {
			Product loaded = productRepository.findById(product.getId()).orElseThrow(); // quantity 10 in memory
			jdbc.update("UPDATE product SET quantity = quantity - 7 WHERE id = ?", product.getId());
			loaded.setTitle("Only the title changes");
			productRepository.save(loaded);
		});

		// only the changed column is written (@DynamicUpdate), so the reservation survives
		assertThat(stock(product)).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT title FROM product WHERE id = ?", String.class, product.getId()))
				.isEqualTo("Only the title changes");
	}
}
