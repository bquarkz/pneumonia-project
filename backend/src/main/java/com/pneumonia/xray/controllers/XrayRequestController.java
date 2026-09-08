package com.pneumonia.xray.controllers;

import com.pneumonia.users.api.LocalUserService;
import com.pneumonia.xray.dtos.XrayRequestResponse;
import com.pneumonia.xray.services.XrayRequestService;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/xray-requests")
public class XrayRequestController {

	private final XrayRequestService xrayRequestService;
	private final LocalUserService localUserService;

	public XrayRequestController(XrayRequestService xrayRequestService, LocalUserService localUserService) {
		this.xrayRequestService = xrayRequestService;
		this.localUserService = localUserService;
	}

	@PostMapping(value = "/batch", consumes = "multipart/form-data")
	@ResponseStatus(HttpStatus.CREATED)
	public List<XrayRequestResponse> uploadBatch(
			@RequestParam("files") List<MultipartFile> files, @AuthenticationPrincipal Jwt jwt) {

		localUserService.resolveOrCreate(jwt);
		return xrayRequestService.uploadBatch(jwt.getSubject(), files);
	}

	@GetMapping
	public List<XrayRequestResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return xrayRequestService.listForUser(jwt.getSubject());
	}

	@PostMapping("/{id}/retry")
	public XrayRequestResponse retry(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		return xrayRequestService.retry(jwt.getSubject(), id);
	}

	/** Only JPEG uploads are accepted (batch validation), so the response is always image/jpeg. */
	@GetMapping("/{id}/image")
	public ResponseEntity<Resource> image(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		Resource resource = xrayRequestService.loadImage(jwt.getSubject(), id);
		return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(resource);
	}
}
