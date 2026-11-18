package com.bazaarly.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.HashSet;
import java.util.Set;

import com.bazaarly.modal.Category;
import com.bazaarly.modal.Size;
import com.bazaarly.user.domain.ProductSize;


public class CreateProductRequest {
	
    @NotBlank(message = "Title is required")
    @jakarta.validation.constraints.Size(max = 255)
    private String title;

    @jakarta.validation.constraints.Size(max = 255)
    private String description;

    @Min(value = 1, message = "Price must be at least 1")
    private int price;

    @Min(value = 1, message = "Discounted price must be at least 1")
    private int discountedPrice;
   
    @Min(0)
    @Max(100)
    private int discountPersent;

    @Min(value = 0, message = "Quantity cannot be negative")
    private int quantity;

    @jakarta.validation.constraints.Size(max = 255)
    private String brand;

    @jakarta.validation.constraints.Size(max = 255)
    private String color;

    @Valid
    private Set<Size> size=new HashSet<>();

    @NotBlank(message = "Image URL is required")
    @jakarta.validation.constraints.Size(max = 255)
    @Pattern(regexp = "https?://.+", message = "Image URL must start with http:// or https://")
    private String imageUrl;

    @NotBlank(message = "Top-level category is required")
    @jakarta.validation.constraints.Size(max = 50)
    private String topLavelCategory;
    @NotBlank(message = "Second-level category is required")
    @jakarta.validation.constraints.Size(max = 50)
    private String secondLavelCategory;
    @NotBlank(message = "Third-level category is required")
    @jakarta.validation.constraints.Size(max = 50)
    private String thirdLavelCategory;

	public Set<Size> getSize() {
		return size;
	}
	public void setSize(Set<Size> size) {
		this.size = size;
	}

	public String getTitle() {
		return title;
	}
	public void setTitle(String title) {
		this.title = title;
	}
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public int getPrice() {
		return price;
	}

	public void setPrice(int price) {
		this.price = price;
	}

	public int getDiscountedPrice() {
		return discountedPrice;
	}

	public void setDiscountedPrice(int discountedPrice) {
		this.discountedPrice = discountedPrice;
	}

	public int getDiscountPersent() {
		return discountPersent;
	}

	public void setDiscountPersent(int discountPersent) {
		this.discountPersent = discountPersent;
	}

	public int getQuantity() {
		return quantity;
	}

	public void setQuantity(int quantity) {
		this.quantity = quantity;
	}

	public String getBrand() {
		return brand;
	}

	public void setBrand(String brand) {
		this.brand = brand;
	}

	public String getColor() {
		return color;
	}

	public void setColor(String color) {
		this.color = color;
	}

	public String getImageUrl() {
		return imageUrl;
	}

	public void setImageUrl(String imageUrl) {
		this.imageUrl = imageUrl;
	}

	public String getTopLavelCategory() {
		return topLavelCategory;
	}

	public void setTopLavelCategory(String topLavelCategory) {
		this.topLavelCategory = topLavelCategory;
	}

	public String getSecondLavelCategory() {
		return secondLavelCategory;
	}

	public void setSecondLavelCategory(String secondLavelCategory) {
		this.secondLavelCategory = secondLavelCategory;
	}

	public String getThirdLavelCategory() {
		return thirdLavelCategory;
	}

	public void setThirdLavelCategory(String thirdLavelCategory) {
		this.thirdLavelCategory = thirdLavelCategory;
	}

	
    
    

	@AssertTrue(message = "Discounted price cannot be higher than the price")
	@com.fasterxml.jackson.annotation.JsonIgnore
	public boolean isDiscountNotAbovePrice() {
		return discountedPrice <= price;
	}
}
