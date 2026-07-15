package com.superpowers.test.storage;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

    String upload(String key, MultipartFile file);
}
